/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.SpecializedDetectionCapabilities;
import cube.aigc.completion.MediaProvider;
import cube.aigc.usage.UsageContext;
import cube.aigc.usage.UsageLedger;
import cube.aigc.usage.UsageTokens;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.AIGCUnit;
import cube.common.entity.FileLabel;
import cube.common.entity.MultimodalInput;
import cube.common.entity.SpecializedDetectionInput;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Harness 媒体桥接：把 Harness 的图像推理请求转发到既有的多模态与专用检测 Unit。
 *
 * <p>本类是宿主侧的<b>薄适配层</b>，实现 {@link MediaProvider}：不持有模型、不接受
 * 外部 URL 或模型代码，只负责把调用方已冻结的执行意图转换为 Unit 动作、登记用量、
 * 等待物理响应并回交回执。图片字节先经 FileStorage 暂存，再以 {@link FileLabel}
 * 的形式交给 Unit。</p>
 *
 * <p>核心契约是<b>至多派发一次</b>。调用方在调用前已持久化执行意图，因此本类既不重试，
 * 也不因超时、取消或断连推断物理执行结束；发送窗口之后若没有 ACK，状态保持 unknown，
 * 物理容量、暂存文件与在途路由一并保留，等待 {@link #reconcile()} 对账。只有拿到真实响应，
 * 或能够证明请求确未发出时，才生成回执。</p>
 *
 * <p>线程模型：{@code invokeMedia} 只把任务提交给服务执行器；{@code onResponse}
 * 由 Cellet 回调线程进入；{@code reconcile} 由服务周期任务串行调用。</p>
 */
public final class HarnessMediaBridge implements MediaProvider {

    /**
     * 宿主 AIGC 服务，提供单元表、执行器、存储、图片保存与 Cellet 通信能力。
     */
    private final AIGCService service;

    /**
     * 服务配置快照，整体替换以避免调用方后续修改影响在途请求。
     *
     * <p>读取 {@code harness.media.dedicated} 与 {@code harness.media.units} 两项。</p>
     */
    private volatile Properties config = new Properties();

    /**
     * 在途请求表，以 {@code serviceSn} 为键，保存目标单元、执行意图、回执消费者与结算状态。
     */
    private final ConcurrentMap<Long, Pending> pending = new ConcurrentHashMap<>();

    /**
     * 已受理但尚未结算的请求 ID 集合，用于判断取消标记是否仍然有效。
     */
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    /**
     * 已请求取消的请求 ID 集合；取消只阻止后续派发，不推翻已发出的物理执行。
     */
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /**
     * 单次媒体调用的在途状态，是发送窗口期间对账与用量结算的唯一凭据。
     */
    private static final class Pending {

        /**
         * 命中的目标单元；未命中时为 {@code null}。
         */
        final AIGCUnit unit;

        /**
         * 调用方冻结的执行意图，含 requestId、instanceId、tenant、deadlineAt 等。
         */
        final JSONObject request;

        /**
         * 回执消费者，由 Harness 负责持久化回执。
         */
        final Consumer<JSONObject> listener;

        /**
         * FileStorage 中暂存图片的文件编码，取值即 requestId。
         */
        final String fileCode;

        /**
         * 图片归属域，取自执行意图的 tenant，用于结算后删除暂存文件。
         */
        final String domain;

        /**
         * 物理响应（或已证实的未发送失败）对应的回执；非空即表示该记录可结算。
         */
        volatile JSONObject receipt;

        /**
         * 用量账本是否已 begin，以及目标单元是否已置为 running。
         */
        volatile boolean usageStarted, runningMarked;

        /**
         * 构造在途状态。
         *
         * @param unit     命中的目标单元。
         * @param request  调用方冻结的执行意图。
         * @param listener 回执消费者。
         * @param fileCode 暂存图片的文件编码。
         */
        Pending(AIGCUnit unit, JSONObject request, Consumer<JSONObject> listener, String fileCode) {
            this.unit = unit;
            this.request = request;
            this.listener = listener;
            this.fileCode = fileCode;
            this.domain = request.getString("tenant");
        }
    }

    /**
     * 构造桥接实例。
     *
     * @param service 宿主 AIGC 服务。
     */
    HarnessMediaBridge(AIGCService service) {
        this.service = service;
    }

    /**
     * 以快照方式替换配置，避免调用方后续修改 {@code Properties} 影响在途请求。
     *
     * @param p 服务配置，仅读取 {@code harness.media.dedicated} 与 {@code harness.media.units}。
     */
    public void configure(Properties p) {
        Properties copy = new Properties();
        copy.putAll(p);
        config = copy;
    }

    /**
     * 计算单元实例标识：{@code 域名:联系人 ID}。
     *
     * @param unit 目标单元。
     * @return 与执行意图中 {@code instanceId} 比对使用的实例标识。
     */
    private String instance(AIGCUnit unit) {
        return unit.getContact().getDomain().getName() + ":" + unit.getContact().getId();
    }

    /**
     * 列出当前可用且被显式启用的媒体路由。
     *
     * <p>仅当服务已启动且 {@code harness.media.dedicated=true} 时返回非空结果；路由由
     * {@code harness.media.units} 白名单按能力名筛选，源码中存在实现不作为就绪判据。
     * 专用检测单元还需校验描述信息与能力就绪状态。</p>
     *
     * @return 路由描述数组，元素含 {@code unit}、{@code instanceId}、{@code capacity}
     *         与 {@code ready}；未启用或无匹配时返回空数组。
     */
    public JSONArray mediaUnits() {
        JSONArray out = new JSONArray();
        if (!service.isStarted() || !Boolean.parseBoolean(config.getProperty("harness.media.dedicated", "false"))) {
            return out;
        }
        Set<String> names = new HashSet<>(Arrays.asList(config.getProperty("harness.media.units", "").split(",")));
        // 白名单由运维显式启用已预热的实际路由，源码树不作为就绪判据。
        for (AIGCUnit unit : service.getAllUnits()) {
            if (names.contains(unit.getCapability().getName())) {
                JSONObject descriptor = SpecializedDetectionInput.UNIT.equals(unit.getCapability().getName())
                        ? service.getSpecializedDetections().descriptor(unit) : new JSONObject();
                boolean ready = unit.getContext() != null && unit.getContext().isValid()
                        && (!SpecializedDetectionInput.UNIT.equals(unit.getCapability().getName())
                        || SpecializedDetectionCapabilities.ready(descriptor));
                out.put(descriptor.put("unit", unit.getCapability().getName()).put("instanceId", instance(unit))
                        .put("capacity", 1).put("ready", ready));
            }
        }
        return out;
    }

    /**
     * 受理一次媒体推理调用，把执行体提交到服务执行器。
     *
     * <p>调用方必须先持久化执行意图；本方法<b>不重试</b>，执行器拒绝时清理标记后原样抛出，
     * 由调用方按既有意图决定处置。请求 ID 在提交前登记为 active。</p>
     *
     * @param execution 调用方冻结的执行意图。
     * @param image     原始图片字节，不接受 URL 或模型代码。
     * @param receipt   回执消费者，仅在物理响应或证实未发出时回调。
     * @throws RejectedExecutionException 执行器拒绝受理，由调用方按持久意图处理。
     */
    public void invokeMedia(JSONObject execution, byte[] image, Consumer<JSONObject> receipt) {
        // 不做任何重试，包括执行器拒绝；调用方已持有持久化的执行意图。
        String id = execution.getString("requestId");
        active.add(id);
        try {
            service.getExecutor().execute(() -> execute(execution, image, receipt));
        } catch (RejectedExecutionException rejected) {
            active.remove(id);
            cancelled.remove(id);
            throw rejected;
        }
    }

    /**
     * 尽力取消：对仍活跃的请求打上取消标记，阻止后续准备与派发。
     *
     * <p>已发出的请求仍需等待物理回执，不会因取消而被推断为终态。</p>
     *
     * @param requestId 待取消的请求 ID。
     */
    public void cancelMedia(String requestId) {
        if (active.contains(requestId)) {
            cancelled.add(requestId);
        }
    }

    /**
     * 判断请求是否应在派发前终止：已被取消，或已超过截止时间。
     *
     * @param request 调用方冻结的执行意图。
     * @return 为 {@code true} 时不再继续发送。
     */
    private boolean expired(JSONObject request) {
        return cancelled.contains(request.getString("requestId")) || System.currentTimeMillis() >= request.getLong("deadlineAt");
    }

    /**
     * 执行一次媒体调用：选中单元、暂存图片、登记用量并发出 Unit 动作。
     *
     * <p>只有在 {@code sent} 置位之前发生的失败，才被认定为「确未发出」：此时生成
     * {@code unsent} 失败回执并立即对账，同时复位单元 running 标志。一旦发出，即使
     * transmit 返回 {@code null} 或连接中断，也<b>不能</b>推断物理执行结束，须保留
     * 暂存文件、在途记录与宿主的容量预留，等待真实响应或运维对账。</p>
     *
     * <p>本地临时文件在 finally 中删除；FileStorage 中的正式暂存文件由
     * {@link #reconcile()} 在回执交付后删除。</p>
     *
     * @param execution 调用方冻结的执行意图。
     * @param image     原始图片字节。
     * @param listener  回执消费者。
     */
    private void execute(JSONObject execution, byte[] image, Consumer<JSONObject> listener) {
        AIGCUnit selected = null;
        for (AIGCUnit unit : service.getAllUnits()) {
            if (unit.getCapability().getName().equals(execution.getString("unit"))
                    && instance(unit).equals(execution.getString("instanceId")) && unit.getContext().isValid()) {
                selected = unit;
            }
        }
        JSONObject unsent = new JSONObject().put("requestId", execution.getString("requestId")).put("instanceId", execution.getString("instanceId"))
                .put("actualFinished", true).put("success", false).put("unsent", true);
        final AIGCUnit unit = selected;
        String code = execution.getString("requestId");
        long sn = execution.getLong("serviceSn");
        boolean sent = false;
        Pending p = new Pending(unit, execution, listener, code);
        pending.put(sn, p);
        Path temporary = null;
        try {
            if (unit == null) {
                throw new IllegalStateException("CAPABILITY_NOT_READY");
            }
            checkDetection(unit, execution);
            if (expired(execution)) {
                throw new IllegalStateException("MEDIA_CANCELLED_BEFORE_SEND");
            }
            if (image.length > 1048576 || image.length == 0) {
                throw new IllegalArgumentException("MEDIA_SIZE");
            }
            temporary = Files.createTempFile("harness-media-", execution.getString("mediaType").equals("image/png") ? ".png" : ".jpg");
            Files.write(temporary, image);
            FileLabel label = service.saveHarnessMedia(execution, code, temporary.toFile());
            if (label == null) {
                throw new IllegalStateException("FILE_STORAGE_UNAVAILABLE");
            }
            if (expired(execution)) {
                throw new IllegalStateException("MEDIA_CANCELLED_BEFORE_SEND");
            }
            JSONObject input = mediaInput(execution, code);
            boolean specialized = execution.getString("adapter").equals("specialized-detection");
            JSONObject data;
            if (specialized) {
                data = new SpecializedDetectionInput(input).toUnitJSON(label);
            } else {
                MultimodalInput multimodal = new MultimodalInput(input);
                multimodal.fileLabels = Collections.singletonList(label);
                data = multimodal.toUnitJson();
            }
            UsageContext usage = UsageContext.run(execution.getString("tenant"), Long.parseLong(execution.getString("subject")), execution.getString("runId"), execution.getString("requestId"));
            service.getStorage().getUsageWriter().begin(usage, execution.getString("requestId"), unit.getCapability().getName(), unit.getQueryKey(), System.currentTimeMillis());
            p.usageStarted = true;
            if (expired(execution)) {
                throw new IllegalStateException("MEDIA_CANCELLED_BEFORE_SEND");
            }
            checkDetection(unit, execution);
            unit.setRunning(true);
            p.runningMarked = true;
            Packet packet = new Packet(sn, specialized ? AIGCAction.SpecializedDetection.name : AIGCAction.Multimodal.name, data);
            listener.accept(new JSONObject().put("requestId", execution.getString("requestId"))
                    .put("instanceId", execution.getString("instanceId")).put("actualFinished", false)
                    .put("stage", "physical-dispatch").put("dispatchedAt", System.currentTimeMillis()).put("serviceSn", sn));
            sent = true; // 此点之后若缺 ACK 即为 unknown，即使 transmit 返回 null 也不代表物理执行结束。
            ActionDialect response = service.getCellet().transmit(unit.getContext(), packet.toDialect(), 180000, sn);
            if (response != null) {
                onResponse(unit.getContext(), response);
            }
        } catch (Exception failure) {
            if (!sent) {
                if (p.runningMarked) {
                    unit.setRunning(false);
                    p.runningMarked = false;
                }
                p.receipt = unsent.put("payload", new JSONObject()).put("finishedAt", System.currentTimeMillis());
                reconcile(); // 即使是已证实未发出的回执也先交付落库，再释放宿主预留。
            }
            // 一旦发出，保留暂存文件、在途路由与宿主的持久容量预留。
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 构造 Unit 输入：能力名、文件编码列表与选项；仅多模态额外携带提示内容。
     *
     * @param execution 调用方冻结的执行意图。
     * @param code      暂存图片的文件编码。
     * @return Unit 输入 JSON。
     */
    private static JSONObject mediaInput(JSONObject execution, String code) {
        JSONObject input = new JSONObject().put("unit", execution.getString("unit"))
                .put("files", new JSONArray().put(code)).put("option", execution.getJSONObject("option"));
        if (!"specialized-detection".equals(execution.getString("adapter"))) {
            input.put("content", execution.getString("content"));
        }
        return input;
    }

    /**
     * 校验专用检测单元的能力：描述信息就绪、图像余量大于零、profile 与切片参数受支持。
     *
     * @param unit      目标单元；非专用检测单元直接通过。
     * @param execution 调用方冻结的执行意图。
     * @throws IllegalStateException 能力未就绪时抛出 {@code CAPABILITY_NOT_READY}。
     */
    private void checkDetection(AIGCUnit unit, JSONObject execution) {
        if (!SpecializedDetectionInput.UNIT.equals(unit.getCapability().getName())) {
            return;
        }
        JSONObject descriptor = service.getSpecializedDetections().descriptor(unit);
        JSONObject option = execution.getJSONObject("option");
        if (!SpecializedDetectionCapabilities.ready(descriptor)
                || descriptor.optInt("availableImages") <= 0
                || !SpecializedDetectionCapabilities.supports(descriptor, option.getString("profile"), option.optBoolean("sliced"))) {
            throw new IllegalStateException("CAPABILITY_NOT_READY");
        }
    }

    /**
     * 处理 Unit 返回的物理响应，生成并暂存回执。
     *
     * <p>只接受与在途记录匹配的响应：同一 {@code sn}、同一通信上下文、动作名一致且负载
     * 携带 {@code code}。重复响应由 {@code p} 上的同步块结合 {@code receipt} 判空去重。
     * 回执剔除 {@code thought} 字段，并记录成功标志、负载与完成时间。</p>
     *
     * @param context 响应所在的通信上下文。
     * @param dialect Unit 返回的动作方言。
     */
    public void onResponse(TalkContext context, ActionDialect dialect) {
        Packet packet;
        try {
            packet = new Packet(dialect);
        } catch (Exception invalid) {
            return;
        }
        Pending p = pending.get(packet.sn);
        if (p == null || p.unit == null || p.unit.getContext() != context) {
            return;
        }
        String expected = p.request.getString("adapter").equals("specialized-detection") ? AIGCAction.SpecializedDetection.name : AIGCAction.Multimodal.name;
        if (!expected.equals(packet.name) || !packet.data.has("code")) {
            return;
        }
        synchronized (p) {
            if (p.receipt != null) {
                return;
            }
            JSONObject payload = packet.data.optJSONObject("data");
            payload = payload == null ? new JSONObject() : new JSONObject(payload.toString());
            payload.remove("thought");
            p.receipt = new JSONObject().put("requestId", p.request.getString("requestId")).put("instanceId", p.request.getString("instanceId"))
                    .put("serviceSn", packet.sn).put("actualFinished", true).put("success", Packet.extractCode(packet) == 0).put("payload", payload)
                    .put("finishedAt", System.currentTimeMillis());
        }
        reconcile();
    }

    /**
     * 结算所有已产生回执的在途记录，由服务周期任务（{@code onTick}）调用。
     *
     * <p>依次结算用量账本、复位单元 running 标志、把回执交给监听器、删除暂存图片，
     * 并清理在途、活跃与取消标记。任一步骤失败时整条记录保留到下一轮重试；
     * <b>只重试回执交付，不重新派发推理</b>。</p>
     */
    public synchronized void reconcile() {
        for (Map.Entry<Long, Pending> entry : pending.entrySet()) {
            Pending p = entry.getValue();
            JSONObject receipt = p.receipt;
            if (receipt == null) {
                continue;
            }
            try {
                long[] tokens = UsageTokens.read(receipt.getJSONObject("payload"));
                if (p.usageStarted) {
                    service.getStorage().getUsageWriter().finish(UsageLedger.evidence(p.request.getString("requestId"), 2,
                            receipt.getBoolean("success") ? "succeeded" : "failed", tokens == null ? "unknown" : "reported",
                            tokens == null ? null : tokens[0], tokens == null ? null : tokens[1], receipt.getLong("finishedAt")));
                }
                if (p.runningMarked) {
                    p.unit.setRunning(false);
                    p.runningMarked = false;
                }
                p.listener.accept(receipt); // 持久化失败则留在队列等待下轮重试，绝不重复推理。
                service.deleteFile(p.domain, p.fileCode);
                pending.remove(entry.getKey(), p);
                active.remove(p.request.getString("requestId"));
                cancelled.remove(p.request.getString("requestId"));
            } catch (Exception retryReceiptOnly) { /* 由 AIGC onTick 下轮重试，不重新派发推理 */ }
        }
    }
}
