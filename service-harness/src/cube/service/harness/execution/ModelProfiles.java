/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.runtime.ContextEngine;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 生成不可变的 ModelProfile（version=2）与传输预算描述符。
 *
 * <p>这里的预算是有版本的线上字节预算（transport-bytes-v1），不宣称知道远端 tokenizer 或
 * chat template；实际 usage 由推理账本单独结算。certification=offline-adapter 仅表示离线
 * 适配验证，不代表真实模型质量验收。
 */
public final class ModelProfiles {

    /**
     * 读取服务内部作业路由使用的 adapter 摘要，不从部署配置接收或校验摘要。
     *
     * <p>以 "-v2" 结尾的 adapter 查 contracts/inference/adapter-profiles.json，
     * 其余查 contracts/execution/adapter-profiles.json；两边在两仓逐字节镜像。
     *
     * @param adapter adapter 标识（如 openai-json-v2、openai-tools-v2）
     * @return 该 adapter 在契约中的 digest 字段值
     * @throws IllegalArgumentException 契约缺失、adapter 未注册或字段缺失，错误码 ADAPTER_PROFILE_MISSING
     */
    public static String adapterDigest(String adapter) {
        try {
            return new JSONObject(
                    new String(cube.service.harness.protocol.Contract.resource(
                            (adapter.endsWith("-v2") ? "inference" : "execution") + "/adapter-profiles.json"),
                            StandardCharsets.UTF_8))
                    .getJSONObject(adapter).getString("digest");
        } catch (Exception e) {
            throw new IllegalArgumentException("ADAPTER_PROFILE_MISSING");
        }
    }

    /**
     * 生成传输预算 tokenizer 描述符：仅声明线上字节上限，远端 tokenizer 未验证。
     *
     * @return tokenizer 描述符，含 id=transport-bytes-v1 与 version
     */
    public static JSONObject tokenizer() {
        return new JSONObject()
                .put("id", "transport-bytes-v1")
                .put("version", "1");
    }

    /**
     * 旧调用方的 Baize/Baize2 兼容入口；其他名称必须使用显式能力重载。
     *
     * <p>adapter 由模型名固定：Baize→openai-json-v2，Baize2→openai-tools-v2；
     * 新模型的 adapter 由显式 nativeTools 决定。generation 固定
     * temperature=0、topP=1、stream=true、maxRepairs=1。
     *
     * @param name 模型名（Baize 或 Baize2）
     * @return 当前格式的 model profile JSON，无需 schemaVersion
     */
    public static JSONObject model(String name) {
        if (!name.equals("Baize") && !name.equals("Baize2"))
            throw new IllegalArgumentException("MODEL_CAPABILITIES_REQUIRED");
        return model(name, name.equals("Baize2"), name.equals("Baize") ? 40960 : 65536);
    }

    /**
     * Generic models require explicit verified native-tool support and a configured context window.
     */
    public static JSONObject model(String name, boolean nativeTools, int contextTokens) {
        cube.service.harness.runtime.RuntimeSupport.id(name);
        if (contextTokens < 128 || contextTokens > 1048576) throw new IllegalArgumentException("MODEL_CONTEXT_INVALID");
        String adapter = nativeTools ? "openai-tools-v2" : "openai-json-v2";
        return new JSONObject().put("kind", "model").put("id", name).put("version", "2")
                .put("backend", "java-inference").put("adapter", adapter)
                .put("capabilities", new JSONArray().put("resource_decisions")).put("tokenizer", tokenizer()).put("template", ContextEngine.template())
                .put("templateOverhead", 4096).put("maxContextTokens", contextTokens).put("certification", "offline-adapter")
                .put("nativeTools", nativeTools)
                .put("generation", new JSONObject().put("temperature", 0).put("topP", 1)
                        .put("stream", true).put("maxRepairs", 1));
    }
}
