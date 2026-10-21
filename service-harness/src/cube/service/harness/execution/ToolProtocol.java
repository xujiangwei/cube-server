/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONObject;

/**
 * 当前通用工具（tool）与专用沙箱（sandbox）两类 runner 的契约定义与校验入口。
 *
 * <p>关键设计点：
 * <ul>
 *   <li>契约资源按 runner 家族目录组织：{@code runner-tool} 与 {@code runner-sandbox}，
 *       与 Python 侧 {@code contracts/} 逐字节镜像，摘要漂移即视为部署损坏；</li>
 *   <li>{@link #verify()} 在 Broker 构造时执行启动自检：逐文件核对 manifest 摘要，
 *       并核对 runtime 清单 sources 的派生摘要，任一不符均拒绝启动；</li>
 *   <li>{@link #parse} 校验所有跨进程线报（runner-job/runner-report/sandbox-receipt 等），
 *       线报格式不合法由契约校验直接拒绝；</li>
 *   <li>错误码约定：{@code RUNNER_TYPE_INVALID}（未知 runner 类型）、
 *       {@code RUNNER_CONTRACT_MISSING}（契约资源缺失，部署不完整）、
 *       {@code RUNNER_CONTRACT_DIGEST_MISMATCH} / {@code RUNNER_SOURCE_DIGEST_MISMATCH}
 *       （契约或源码摘要不符）。</li>
 * </ul>
 */
public final class ToolProtocol {
    /**
     * 通用工具 runner 类型标识，对应契约目录 runner-tool。
     */
    public static final String TOOL = "tool";

    /**
     * 专用沙箱 runner 类型标识，对应契约目录 runner-sandbox。
     */
    public static final String SANDBOX = "sandbox";

    /**
     * 将 runner 类型映射为契约根目录名，并拒绝未知类型。
     *
     * @param runnerType runner 类型标识，只能是 {@link #TOOL} 或 {@link #SANDBOX}
     * @return 契约根目录名，形如 {@code runner-tool} / {@code runner-sandbox}
     * @throws IllegalArgumentException 类型不是 tool/sandbox 时抛出，错误码为
     *                                  {@code RUNNER_TYPE_INVALID}
     */
    private static String family(String runnerType) {
        if (!TOOL.equals(runnerType) && !SANDBOX.equals(runnerType)) {
            throw new IllegalArgumentException("RUNNER_TYPE_INVALID");
        }
        return "runner-" + runnerType;
    }

    /**
     * 按契约 schema 解析并校验一段跨进程线报字节。
     *
     * <p>除 sandbox-receipt 固定归属 sandbox 家族外，其余 schema 依据报文自带的
     * runnerType 选择家族（缺省按 tool）。
     *
     * @param schema 线报 schema 名，如 runner-job、runner-report、runner-response、sandbox-receipt
     * @param raw    线报字节，必须是严格 JSON（无重复键、无注释、无尾随内容）
     * @return 校验通过的线报对象
     * @throws IllegalStateException 契约资源缺失时抛出，错误码为 {@code RUNNER_CONTRACT_MISSING}
     */
    public static JSONObject parse(String schema, byte[] raw) {
        try {
            JSONObject value = StrictJson.object(raw);
            String root = family(schema.equals("sandbox-receipt") ? SANDBOX
                    : value.optString("runnerType", TOOL));
            Contract.validate(
                    value, StrictJson.object(Contract.resource(root + "/schemas/" + schema + ".json")));
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }

    /**
     * 返回默认（tool）runner 的运行时清单。
     *
     * @return runtime 清单对象，含 sources 摘要 digest 等字段
     * @throws IllegalStateException 契约资源缺失时抛出，错误码为 {@code RUNNER_CONTRACT_MISSING}
     */
    public static JSONObject runtime() {
        return runtime(TOOL);
    }

    /**
     * 返回指定 runner 类型的运行时清单。
     *
     * @param runnerType runner 类型标识，只能是 {@link #TOOL} 或 {@link #SANDBOX}
     * @return runtime 清单对象，含 sources 摘要 digest 等字段
     * @throws IllegalArgumentException 类型非法时抛出，错误码为 {@code RUNNER_TYPE_INVALID}
     * @throws IllegalStateException    契约资源缺失时抛出，错误码为 {@code RUNNER_CONTRACT_MISSING}
     */
    public static JSONObject runtime(String runnerType) {
        String root = family(runnerType);
        try {
            return StrictJson.object(
                    Contract.resource(root + "/runtime.json"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }

    /**
     * 执行契约启动自检：两类 runner 家族的全部文件摘要与 runtime 源码摘要。
     *
     * <p>任一文件摘要与 manifest 不符、或 runtime 的 sources 派生摘要与清单 digest 不符，
     * 均视为部署损坏，直接拒绝启动，避免带病节点参与作业路由。
     *
     * @throws IllegalArgumentException 摘要不符时抛出，错误码为
     *                                  {@code RUNNER_CONTRACT_DIGEST_MISMATCH} 或
     *                                  {@code RUNNER_SOURCE_DIGEST_MISMATCH}
     * @throws IllegalStateException    契约资源缺失时抛出，错误码为 {@code RUNNER_CONTRACT_MISSING}
     */
    public static void verify() {
        try {
            for (String root : new String[]{"runner-tool", "runner-sandbox"}) {
                JSONObject manifest = StrictJson.object(Contract.resource(root + "/manifest.json"));
                for (String p : manifest.keySet()) {
                    if (!RuntimeSupport.sha(Contract.resource(root + "/" + p)).equals(manifest.getString(p))) {
                        throw new IllegalArgumentException("RUNNER_CONTRACT_DIGEST_MISMATCH");
                    }
                }
            }
            for (String type : new String[]{TOOL, SANDBOX}) {
                JSONObject runtime = runtime(type);
                if (!cube.service.harness.store.JdbcRunStore.hash(runtime.getJSONObject("sources"))
                        .equals(runtime.getString("digest"))) {
                    throw new IllegalArgumentException("RUNNER_SOURCE_DIGEST_MISMATCH");
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }
}
