/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.runtime.ContextEngine;
import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 生成不可变的 ModelProfile（schemaVersion=3/version=2）与传输预算描述符。
 *
 * <p>这里的预算是有版本的线上字节预算（transport-bytes-v1），不宣称知道远端 tokenizer 或
 * chat template；实际 usage 由推理账本单独结算。certification=offline-adapter 仅表示离线
 * 适配验证，不代表真实模型质量验收。
 */
public final class ModelProfiles {

    /**
     * 计算 adapter 标识的 SHA-256 摘要（UTF-8 字节）。
     *
     * @param adapter adapter 标识字符串
     * @return 十六进制摘要；空串得到空串的摘要
     */
    public static String digest(String adapter) {
        return RuntimeSupport.sha(adapter.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读取契约中注册的 adapter 摘要；缺失或解析失败一律拒绝。
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
     * @return tokenizer 描述符，含 id=transport-bytes-v1、version 与摘要
     */
    public static JSONObject tokenizer() {
        return new JSONObject()
                .put("id", "transport-bytes-v1")
                .put("version", "1")
                .put("digest", digest("transport-bytes-v1:wire-limit-only;remote-tokenizer-unverified"));
    }

    /**
     * 生成指定模型的 ModelProfile；Baize 走严格 JSON，其余（Baize2）走原生工具。
     *
     * <p>adapter 由模型名固定：Baize→openai-json-v2，Baize2→openai-tools-v2；
     * nativeTools 仅 Baize2 为 true，调用方不能按名称猜测能力。generation 固定
     * temperature=0、topP=1、stream=true、maxRepairs=1。
     *
     * @param name 模型名（Baize 或 Baize2）
     * @return schemaVersion=3 的 model profile JSON
     * @throws IllegalArgumentException adapter 契约缺失（见 {@link #adapterDigest}）
     */
    public static JSONObject model(String name) {
        String adapter = name.equals("Baize") ? "openai-json-v2" : "openai-tools-v2";
        return new JSONObject().put("schemaVersion", 3).put("kind", "model").put("id", name).put("version", "2")
                .put("backend", "java-inference").put("adapter", adapter).put("adapterDigest", adapterDigest(adapter))
                .put("capabilities", new JSONArray().put("resource_decisions")).put("tokenizer", tokenizer()).put("template", ContextEngine.template())
                .put("templateOverhead", 4096).put("maxContextTokens", 65536).put("certification", "offline-adapter")
                .put("nativeTools", name.equals("Baize2"))
                .put("generation", new JSONObject().put("temperature", 0).put("topP", 1)
                        .put("stream", true).put("maxRepairs", 1));
    }
}
