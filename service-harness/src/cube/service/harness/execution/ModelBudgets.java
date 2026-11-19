/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.runtime.ExecutionProfiles;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 受信的服务端上下文计量：以服务端计数为准，Worker 上报的 token 数永不作为推理授权依据。
 */
public final class ModelBudgets {
    /**
     * 可信 token 计数器扩展点。
     *
     * <p>实现须绑定模型、adapter、tokenizer 资源与 chat 模板版本：{@code countContext} 计入完整的
     * adapter 提示词、工具与对话框架，而非序列化后的 JSON token；{@code countWire} 计入实际推理提示词，
     * 含格式修复消息。只安装与部署后端核对过的计数器，此处不认证任何通用计数器。
     * 实现必须线程安全、结果确定，且计数期间不做网络访问或资源加载。</p>
     */
    public interface Counter {
        /**
         * 判断本计数器是否适用于给定模型。
         *
         * @param model 模型配置。
         * @return 适用返回 {@code true}。
         */
        boolean supports(JSONObject model);

        /**
         * 计量完整上下文的 token 数。
         *
         * @param model   模型配置。
         * @param request 请求对象。
         * @return 上下文 token 数。
         */
        long countContext(JSONObject model, JSONObject request);

        /**
         * 计量实际推理提示词的 token 数。
         *
         * @param model 模型配置。
         * @param wire  实际推理提示词。
         * @return 推理提示词 token 数。
         */
        long countWire(JSONObject model, JSONObject wire);
    }

    /**
     * 已安装的可信计数器列表。
     */
    private final List<Counter> counters;

    /**
     * 依据给定计数器创建预算计量器。
     *
     * @param counters 已安装的可信计数器。
     */
    public ModelBudgets(Counter... counters) {
        this.counters = Arrays.asList(counters);
    }

    /**
     * 从运行参数装载计数器实现类。
     *
     * @param properties 运行参数；{@code model.token.counter.classes} 为逗号分隔的计数器类名。
     * @return 装载完成的预算计量器。
     */
    public static ModelBudgets load(Properties properties) {
        List<Counter> result = new ArrayList<>();
        for (String name : properties.getProperty("model.token.counter.classes", "").split(",")) {
            if (name.trim().isEmpty()) {
                continue;
            }
            try {
                result.add((Counter) Class.forName(name.trim()).getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                throw new IllegalArgumentException("MODEL_COUNTER_CONFIGURATION_INVALID", e);
            }
        }
        return new ModelBudgets(result.toArray(new Counter[0]));
    }

    /**
     * 模型是否使用精确计数，而非按字节估算的传输预算 tokenizer。
     *
     * @param model 模型配置。
     * @return 非字节预算 tokenizer 返回 {@code true}。
     */
    public boolean exact(JSONObject model) {
        return !Arrays.asList("transport-bytes-v1", "utf8-bytes-v1").contains(model.getJSONObject("tokenizer").getString("id"));
    }

    /**
     * 选出唯一适用于该模型的计数器。
     *
     * @param model 模型配置。
     * @return 唯一匹配的计数器。
     */
    public Counter require(JSONObject model) {
        Counter selected = null;
        for (Counter counter : counters) {
            if (supports(counter, model)) {
                if (selected != null) {
                    throw RuntimeSupport.error("MODEL_COUNTER_AMBIGUOUS");
                }
                selected = counter;
            }
        }
        if (selected == null) {
            throw RuntimeSupport.error("MODEL_COUNTER_UNAVAILABLE");
        }
        return selected;
    }

    /**
     * 以副本为输入调用计数器，防止计数器篡改调用方对象。
     *
     * @param counter 目标计数器。
     * @param model   模型配置。
     * @return 计数器是否声明支持该模型。
     */
    private boolean supports(Counter counter, JSONObject model) {
        try {
            return counter.supports(RuntimeSupport.copy(model));
        } catch (RuntimeException error) {
            throw RuntimeSupport.error("MODEL_COUNTER_INVALID");
        }
    }

    /**
     * 计量上下文或推理提示词的 token 数，并校验计数结果合法。
     *
     * @param model   模型配置。
     * @param request 请求对象或推理提示词。
     * @param wire    是否计量推理提示词。
     * @return 合法的 token 数。
     */
    private long count(JSONObject model, JSONObject request, boolean wire) {
        Counter counter = require(model);
        try {
            return valid(wire ? counter.countWire(RuntimeSupport.copy(model), RuntimeSupport.copy(request))
                    : counter.countContext(RuntimeSupport.copy(model), RuntimeSupport.copy(request)));
        } catch (RuntimeException error) {
            throw RuntimeSupport.error("MODEL_COUNTER_INVALID");
        }
    }

    /**
     * 校验计数器返回值落在允许区间内。
     *
     * @param value 计数器返回值。
     * @return 校验通过的 token 数。
     */
    private static long valid(long value) {
        if (value < 0 || value > 1000000000L) {
            throw RuntimeSupport.error("MODEL_COUNTER_INVALID");
        }
        return value;
    }

    /**
     * 计量请求的上下文 token 数；java-inference 且带响应 schema 时同时核对实际推理提示词。
     *
     * @param model         模型配置。
     * @param request       请求对象。
     * @param renderedBytes 已渲染请求的 UTF-8 字节数，用于非精确计数器的估算。
     * @return 上下文 token 数。
     */
    public long context(JSONObject model, JSONObject request, int renderedBytes) {
        if (model.optString("backend").equals("java-inference") && request.has("responseSchema")) {
            JSONObject wire = ModelWire.prepare(model, request);
            return exact(model) ? Math.max(count(model, request, false), count(model, wire, true))
                    : (long) JdbcRunStore.canonical(wire).getBytes(StandardCharsets.UTF_8).length + model.getInt("templateOverhead");
        }
        return exact(model) ? count(model, request, false)
                : (long) renderedBytes + model.getInt("templateOverhead");
    }

    /**
     * 在作业身份、profile 与幂等校验之后、入队或派发之前，校验推理提示词未超上下文预算。
     *
     * @param pin  已固定的运行配置。
     * @param wire 待派发的推理提示词。
     */
    public void admitWire(JSONObject pin, JSONObject wire) {
        JSONObject model = pin.getJSONObject("model");
        JSONObject policy = ExecutionProfiles.execution(pin).getJSONObject("contextPolicy");
        if (!exact(model) && !policy.optBoolean("finalWireGuard", false)) {
            return; // 兼容旧的固定 run。
        }
        int bytes = JdbcRunStore.canonical(wire).getBytes(StandardCharsets.UTF_8).length;
        long tokens = exact(model) ? count(model, wire, true)
                : (long) bytes + model.getInt("templateOverhead");
        if (bytes > policy.getInt("maxInputBytes") || tokens > (long) model.getInt("maxContextTokens") - wire.optLong("max_tokens", wire.optLong("max_completion_tokens", 0))) {
            throw RuntimeSupport.error("INFERENCE_CONTEXT_BUDGET_EXCEEDED");
        }
    }
}
