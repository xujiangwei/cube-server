/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.aigc.completion.CompletionError;
import cube.aigc.completion.CompletionProvider;
import cube.aigc.completion.OpenAICompletionProtocol;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.InferenceStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Java 推理网关：Cell 的 inference_* 提交短小，由独立派发线程先落账再调用后端。
 *
 * <p>链路为 Java 受理 → Cell v3（inference_submit/poll/cancel）→ 后端 provider；usage 与
 * 物理结束证据以 Java 账本为准，忽略 Python 的自行断言。派发前先 ledger.submit 持久化，
 * 再 ledger.claim（node+owner 唯一认领）后调用提供方；调用是否到达未知时以
 * MODEL_COMPLETION_UNKNOWN 收尾并保留物理容量，禁止自动重发。内部提供方保留 Unit 观察与
 * 队列占用；外部 OpenAI 后端由配置显式授予容量。
 */
public final class InferenceGateway implements AutoCloseable {

    /**
     * 推理账本：submit/claim/append/finish/reconcile/page 的持久事实来源。
     */
    public final InferenceStore ledger;

    /**
     * Harness 配置（backend.&lt;id&gt;.* 与 inference.node.id）。
     */
    private final Properties config;

    /**
     * 同 JVM Unit 提供方工厂（backend type=unit 时使用）。
     */
    private final Supplier<CompletionProvider> unitProvider;

    /**
     * 外部 OpenAI 提供方缓存，按 backendId 维度唯一（双重检查加锁创建）。
     */
    private final Map<String, OpenAIHttpProvider> external = new ConcurrentHashMap<>();

    /**
     * 单线程派发器（harness-inference-dispatch，daemon），50ms 轮询持久队列。
     */
    private final ScheduledExecutorService dispatcher;

    /**
     * 本推理节点标识；须稳定且部署实例唯一，符合 [A-Za-z0-9_.:-]{1,128}。
     */
    private final String node;

    /**
     * 本 JVM 实例的认领归属（随机 UUID），与 node 共同构成 claim/finish 身份。
     */
    private final String owner = UUID.randomUUID().toString();

    /**
     * 最近一次后端/派发诊断错误码（仅运维可见，不参与协议）。
     */
    private volatile String lastError;

    /**
     * 用账本、配置与 Unit 提供方工厂构造网关；校验节点标识合法。
     *
     * @param ledger       推理账本
     * @param config       Harness 配置
     * @param unitProvider 同 JVM CompletionProvider 工厂；无 Unit 部署时可为返回 null 的工厂
     * @throws IllegalArgumentException inference.node.id 不匹配 [A-Za-z0-9_.:-]{1,128}，
     *                                  错误码 INVALID_INFERENCE_NODE
     */
    public InferenceGateway(InferenceStore ledger, Properties config, Supplier<CompletionProvider> unitProvider) {
        this.ledger = ledger;
        this.config = config;
        this.unitProvider = unitProvider;
        node = config.getProperty("inference.node.id", "harness-" + config.getProperty("http.port", "7088"));
        if (!node.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("INVALID_INFERENCE_NODE");
        }
        dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "harness-inference-dispatch");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 恢复本节点未决窗口并启动派发循环。
     *
     * <p>recover 把该 node 原有 dispatched 窗口转为 unknown，避免 JVM 重启后重复发送；
     * 之后每 50ms 扫描一次持久队列。
     */
    public void start() {
        ledger.recover(node, owner);
        dispatcher.scheduleWithFixedDelay(this::dispatch, 0, 50, TimeUnit.MILLISECONDS);
    }

    /**
     * 读取最近一次诊断错误码。
     *
     * @return 错误码；无故障时为 null
     */
    public String lastError() {
        return lastError;
    }

    /**
     * 读取 backend.&lt;backend&gt;.&lt;key&gt; 配置，缺失时回落默认值。
     *
     * @param backend  后端标识
     * @param key      配置键（不含前缀）
     * @param fallback 缺失时的默认值
     * @return 配置值或默认值
     */
    private String property(String backend, String key, String fallback) {
        return config.getProperty("backend." + backend + "." + key, fallback);
    }

    /**
     * 按类型获取后端提供方：unit 走同 JVM 工厂，openai 走缓存的外部 HTTP 提供方。
     *
     * <p>openai 首次使用才创建，容量必须为 1..32；其余类型明确拒绝。
     *
     * @param backend 后端标识
     * @return 提供方；unit 工厂返回 null 时表示无可用 Unit
     * @throws Exception                Unit 工厂自身抛出的异常
     * @throws IllegalArgumentException 类型非法（INVALID_BACKEND_TYPE）或容量越界
     *                                  （INVALID_BACKEND_CAPACITY）
     */
    private CompletionProvider provider(String backend) throws Exception {
        String type = property(backend, "type", "unit");
        if (type.equals("unit")) {
            return unitProvider.get();
        }
        if (!type.equals("openai")) {
            throw new IllegalArgumentException("INVALID_BACKEND_TYPE");
        }
        OpenAIHttpProvider p = external.get(backend);
        if (p == null) {
            synchronized (external) {
                p = external.get(backend);
                if (p == null) {
                    int capacity = Integer.parseInt(property(backend, "capacity", "0"));
                    if (capacity < 1 || capacity > 32) {
                        throw new IllegalArgumentException("INVALID_BACKEND_CAPACITY");
                    }
                    p = new OpenAIHttpProvider(property(backend, "baseUrl", ""), property(backend, "model", ""),
                            property(backend, "token", ""), capacity);
                    external.put(backend, p);
                }
            }
        }
        return p;
    }

    /**
     * 探测描述符对应的后端是否就绪：openai 类型创建成功即可，unit 类型须模型与能力匹配。
     *
     * <p>unit 后端枚举 completionModels，按模型 id 匹配后校验 text 能力，nativeTools 为真时
     * 还要求 tools 能力；任何异常记 INFERENCE_BACKEND_NOT_READY 并返回 false。
     *
     * @param descriptor worker 描述符（backendId、model、nativeTools）
     * @return 后端就绪且模型能力满足描述符时为 true
     */
    public boolean ready(JSONObject descriptor) {
        String backend = descriptor.getString("backendId");
        try {
            CompletionProvider p = provider(backend);
            if (p == null) {
                return false;
            }
            if (property(backend, "type", "unit").equals("openai")) {
                return true;
            }
            JSONArray models = p.completionModels(property(backend, "token", ""));
            for (int n = 0; n < models.length(); n++) {
                JSONObject model = models.getJSONObject(n);
                if (model.getString("id").equals(descriptor.getString("model"))) {
                    JSONObject caps = model.optJSONObject("capabilities");
                    return caps != null && caps.optBoolean("text") && (!descriptor.getBoolean("nativeTools") || caps.optBoolean("tools"));
                }
            }
        } catch (Exception e) {
            lastError = "INFERENCE_BACKEND_NOT_READY";
        }
        return false;
    }

    /**
     * 查询描述符对应后端的容量（registered/available）。
     *
     * @param descriptor worker 描述符（backendId、model、nativeTools）
     * @return 后端容量报告；任何异常回落 {registered:0, available:0} 并记
     * INFERENCE_BACKEND_NOT_READY
     */
    public JSONObject capacity(JSONObject descriptor) {
        String backend = descriptor.getString("backendId");
        try {
            CompletionProvider p = provider(backend);
            if (p != null) {
                return p.completionCapacity(property(backend, "token", ""),
                        descriptor.getString("model"), descriptor.getBoolean("nativeTools"));
            }
        } catch (Exception e) {
            lastError = "INFERENCE_BACKEND_NOT_READY";
        }
        return new JSONObject().put("registered", 0).put("available", 0);
    }

    /**
     * 受理 Cell 的 inference 动作：submit 先规范化并落账，随后返回增量事件页。
     *
     * <p>inference_submit 对 requestJson 做严格解析与 OpenAI 规范化，参数错误映射为
     * INVALID_INFERENCE_PARAMETER_&lt;PARAM&gt;（参数名大写、非字母数字转下划线、截断 80）；
     * 落账后按 job/worker/instance/attempt 维度返回事件分页，inference_cancel 时标记取消。
     *
     * @param envelope Cell 动作信封（action、jobId、workerId、jobInstanceId、wireAttempt、
     *                 instanceId、requestHash、requestJson、after）
     * @return 该推理的事件页（含增量事件与游标）
     * @throws IllegalArgumentException INVALID_INFERENCE_PARAMETER_* 参数拒绝
     */
    public JSONObject apply(JSONObject envelope) {
        String action = envelope.getString("action");
        String job = envelope.getString("jobId");
        String worker = envelope.getString("workerId");
        String instance = envelope.getString("jobInstanceId");
        int attempt = envelope.getInt("wireAttempt");
        if (action.equals("inference_submit")) {
            JSONObject body = StrictJson.object(envelope.getString("requestJson").getBytes(StandardCharsets.UTF_8));
            try {
                OpenAICompletionProtocol.normalize(body, false);
            } catch (CompletionError e) {
                String param = e.param == null ? "BODY" : e.param.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
                throw new IllegalArgumentException("INVALID_INFERENCE_PARAMETER_" + param.substring(0, Math.min(80, param.length())));
            }
            ledger.submit(job, worker, instance, envelope.getString("instanceId"), attempt, envelope.getString("requestHash"), body);
        }
        return ledger.page(job, worker, instance, attempt, envelope.optLong("after", 0), action.equals("inference_cancel"));
    }

    /**
     * 派发一轮持久队列：规范化、容量检查、claim 后调用提供方，事件回调落账。
     *
     * <p>容量不足或认领失败（他节点已认领）时跳过留待下轮；调用抛出异常时可能已到达提供方，
     * 以 MODEL_COMPLETION_UNKNOWN 收尾并保留物理容量。任一步骤的配置错误记
     * INFERENCE_BACKEND_CONFIGURATION_ERROR；整轮异常记 INFERENCE_DISPATCH_FAILED。
     */
    private void dispatch() {
        try {
            JSONArray queued = ledger.queued();
            for (int n = 0; n < queued.length(); n++) {
                JSONObject i = queued.getJSONObject(n);
                String backend = i.getString("backend_id");
                CompletionProvider p;
                try {
                    p = provider(backend);
                } catch (Exception e) {
                    lastError = "INFERENCE_BACKEND_CONFIGURATION_ERROR";
                    continue;
                }
                if (p == null) {
                    continue;
                }
                JSONObject wire = StrictJson.object(i.getString("request_json").getBytes(StandardCharsets.UTF_8));
                JSONObject canonical = OpenAICompletionProtocol.normalize(wire, false);
                String id = i.getString("inference_id");
                int seconds;
                try {
                    seconds = Integer.parseInt(property(backend, "timeoutSeconds", "180"));
                    if (seconds < 1 || seconds > 600) {
                        throw new IllegalArgumentException("INVALID_BACKEND_TIMEOUT");
                    }
                    if (p.completionCapacity(property(backend, "token", ""), wire.getString("model"),
                            wire.optJSONArray("tools") != null).getInt("available") < 1) {
                        continue;
                    }
                } catch (Exception e) {
                    lastError = "INFERENCE_BACKEND_CONFIGURATION_ERROR";
                    continue;
                }
                if (!ledger.claim(id, node, owner)) {
                    continue;
                }
                try {
                    if (property(backend, "type", "unit").equals("unit")) {
                        p.submitCompletion(property(backend, "token", ""), id, canonical, seconds * 1000L,
                                ledger.accountingContext(id).delegate(), event -> accept(id, wire.getString("model"), event));
                    } else {
                        p.submitCompletion(property(backend, "token", ""), id, canonical, seconds * 1000L,
                                event -> accept(id, wire.getString("model"), event));
                    }
                } catch (Exception e) {
                    // 调用可能已到达提供方；保留预约，以 unknown 收尾，不自动重发。
                    ledger.finish(id, null, "MODEL_COMPLETION_UNKNOWN", false, null);
                }
            }
        } catch (Exception e) {
            lastError = "INFERENCE_DISPATCH_FAILED";
        }
    }

    /**
     * 提供方事件回调：completed/error/delta 分别落账，异常一律按输出非法收尾。
     *
     * <p>completed 优先用原始响应，缺省时由 payload.result 重建；error 的错误码经大写与
     * [A-Z0-9_]{1,128} 校验，非法回落 MODEL_BACKEND_ERROR；delta 转为
     * chat.completion.chunk 增量帧。回调内异常以 MODEL_OUTPUT_INVALID 收尾。
     *
     * @param id    推理 ID
     * @param model 模型名（用于补全响应中的 model 字段）
     * @param event 提供方事件（kind 为 completed/error/delta）
     */
    private void accept(String id, String model, JSONObject event) {
        try {
            String kind = event.getString("kind");
            if (kind.equals("completed")) {
                JSONObject response = event.optJSONObject("rawResponse");
                if (response == null) {
                    response = OpenAICompletionProtocol.result(event.getJSONObject("payload").getJSONObject("result"),
                            false, id, model, System.currentTimeMillis() / 1000);
                }
                ledger.finish(id, response, null, true, event.optString("unitId", null));
            } else if (kind.equals("error")) {
                String code = event.getJSONObject("payload").getJSONObject("error").optString("code", "MODEL_BACKEND_ERROR").toUpperCase(Locale.ROOT);
                if (!code.matches("[A-Z0-9_]{1,128}")) {
                    code = "MODEL_BACKEND_ERROR";
                }
                if (!event.optBoolean("modelDispatched", true)) code = "MODEL_NOT_DISPATCHED";
                ledger.finish(id, null, code, event.optBoolean("actualFinished", false), event.optString("unitId", null), event.optJSONObject("payload"));
            } else if (kind.equals("delta")) {
                JSONObject chunk = new JSONObject().put("id", id).put("model", model).put("object", "chat.completion.chunk")
                        .put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                                .put("delta", event.getJSONObject("payload").getJSONObject("delta")).put("finish_reason", JSONObject.NULL)));
                ledger.append(id, chunk);
            }
        } catch (Exception e) {
            ledger.finish(id, null, "MODEL_OUTPUT_INVALID", event.optBoolean("actualFinished", false), event.optString("unitId", null));
        }
    }

    /**
     * 关闭派发线程并释放外部提供方；中断派发线程时恢复中断标记。
     */
    @Override
    public void close() {
        dispatcher.shutdownNow();
        ledger.closeAccounting();
        try {
            dispatcher.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (OpenAIHttpProvider p : external.values()) {
            p.close();
        }
    }
}
