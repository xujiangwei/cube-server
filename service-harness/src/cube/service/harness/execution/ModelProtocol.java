/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 执行/推理契约的完整性校验与协议编解码：manifest 摘要核对、schema 严格校验、
 * model-outcome 语义约束。所有拒绝均使用稳定错误码，不泄露底层异常细节。
 */
public final class ModelProtocol {

    /**
     * 启动时核对全部契约摘要：manifest 逐文件 SHA-256、adapter-profiles 的 spec 摘要。
     *
     * <p>任何一处不匹配都拒绝启动；契约在两仓逐字节镜像，摘要漂移意味着跨端不一致。
     *
     * @throws IllegalArgumentException EXECUTION/INFERENCE_CONTRACT_DIGEST_MISMATCH、
     *                                  ADAPTER_PROFILE_DIGEST_MISMATCH 或契约读取失败的
     *                                  EXECUTION_SCHEMA_MISSING
     */
    public static void verify() {
        try {
            JSONObject manifest = StrictJson.object(Contract.resource("execution/manifest.json"));
            for (String path : manifest.keySet()) {
                if (!RuntimeSupport.sha(Contract.resource("execution/" + path)).equals(manifest.getString(path))) {
                    throw new IllegalArgumentException("EXECUTION_CONTRACT_DIGEST_MISMATCH");
                }
            }
            JSONObject inference = StrictJson.object(Contract.resource("inference/manifest.json"));
            for (String path : inference.keySet()) {
                if (!RuntimeSupport.sha(Contract.resource("inference/" + path)).equals(inference.getString(path))) {
                    throw new IllegalArgumentException("INFERENCE_CONTRACT_DIGEST_MISMATCH");
                }
            }
            JSONObject current = StrictJson.object(Contract.resource("inference/adapter-profiles.json"));
            for (String name : current.keySet()) {
                JSONObject profile = current.getJSONObject(name);
                if (!cube.service.harness.store.JdbcRunStore.hash(profile.getJSONObject("spec")).equals(profile.getString("digest"))) {
                    throw new IllegalArgumentException("ADAPTER_PROFILE_DIGEST_MISMATCH");
                }
            }
            JSONObject profiles = StrictJson.object(Contract.resource("execution/adapter-profiles.json"));
            for (String name : profiles.keySet()) {
                JSONObject p = profiles.getJSONObject(name);
                if (!cube.service.harness.store.JdbcRunStore.hash(p.getJSONObject("spec")).equals(p.getString("digest"))) {
                    throw new IllegalArgumentException("ADAPTER_PROFILE_DIGEST_MISMATCH");
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("EXECUTION_SCHEMA_MISSING");
        }
    }

    /**
     * 严格解析字节并按对应 schema 校验；schema 名前缀决定契约目录。
     *
     * <p>model-worker-v3- / model-v3- 前缀查 contracts/inference/，其余查 contracts/execution/。
     *
     * @param schema schema 名（不含目录与 .json 后缀）
     * @param raw    待解析的 JSON 字节
     * @return 校验通过的 JSON 对象
     * @throws IllegalArgumentException 解析/校验失败或契约读取不到的 EXECUTION_SCHEMA_MISSING
     */
    public static JSONObject parse(String schema, byte[] raw) {
        try {
            JSONObject v = StrictJson.object(raw);
            Contract.validate(v, StrictJson.object(Contract.resource(
                    (schema.startsWith("model-worker-v3-") || schema.startsWith("model-v3-") ? "inference" : "execution")
                            + "/schemas/" + schema + ".json")));
            return v;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("EXECUTION_SCHEMA_MISSING");
        }
    }

    /**
     * 解析并校验 model-outcome：键集合、status 与 finished 的一致性、decision/errorCode/usage 约束。
     *
     * <p>关键不变量：status=unknown 当且仅当 finished=false（物理结束未确认）；success 禁止
     * errorCode 且 decision 必须过 model-decision schema；error/unknown 禁止 decision，
     * errorCode 必须匹配 [A-Z0-9_]{1,128}，unknown 还禁止 usage。usage 仅接受
     * {input,output} 两个非负整数（≤10^12），缺失即缺失，不估算。audit 最多 2 条。
     * cancellationObserved 必须是真实布尔值。
     *
     * @param raw      outcome JSON 文本
     * @param finished worker 断言的物理结束标记；unknown 状态必须与之互斥配对
     * @return 校验通过的 outcome 对象（未做任何字段补写）
     * @throws IllegalArgumentException MODEL_OUTCOME_INVALID / MODEL_USAGE_INVALID /
     *                                  EXECUTION_SCHEMA_MISSING
     */
    public static JSONObject outcome(String raw, boolean finished) {
        JSONObject o = StrictJson.object(raw.getBytes(StandardCharsets.UTF_8));
        parse("model-outcome", raw.getBytes(StandardCharsets.UTF_8));
        Set<String> allowed = new HashSet<>(Arrays.asList("status", "decision", "errorCode", "usage", "audit", "cancellationObserved"));
        if (!allowed.containsAll(o.keySet()) || !o.has("status") || !o.has("audit") || !o.has("cancellationObserved")) {
            throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        }
        String status = o.getString("status");
        if (!Arrays.asList("success", "error", "unknown").contains(status) || finished == status.equals("unknown")) {
            throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        }
        if (status.equals("success")) {
            if (o.has("errorCode")) {
                throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
            }
            Contract.parse("model-decision", RuntimeSupport.bytes(o.getJSONObject("decision")));
        } else if (o.has("decision") || !o.optString("errorCode").matches("[A-Z0-9_]{1,128}") || status.equals("unknown") && o.has("usage")) {
            throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        }
        JSONArray audit = o.getJSONArray("audit");
        if (audit.length() > 2) {
            throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        }
        if (o.has("usage")) {
            JSONObject u = o.getJSONObject("usage");
            if (!u.keySet().equals(new HashSet<>(Arrays.asList("input", "output")))) {
                throw new IllegalArgumentException("MODEL_USAGE_INVALID");
            }
            for (String k : u.keySet()) {
                Object n = u.get(k);
                if (!(n instanceof Number) || new java.math.BigDecimal(n.toString()).scale() > 0 || u.getLong(k) < 0 || u.getLong(k) > 1000000000000L) {
                    throw new IllegalArgumentException("MODEL_USAGE_INVALID");
                }
            }
        }
        if (o.optBoolean("cancellationObserved", false) != o.getBoolean("cancellationObserved")) {
            throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        }
        return o;
    }
}
