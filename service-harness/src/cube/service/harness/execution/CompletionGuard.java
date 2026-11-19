/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/**
 * 部署安装的业务完成度校验扩展点。
 *
 * <p>校验逻辑由管理员随部署安装，上传的资源包无法安装 Java 代码，因此业务规则与证据判定
 * 只来自受信的扩展实现。宿主在包激活与运行期调用本接口，读取当前 run 的受信资源，
 * 并以宿主已确认的 Provider/作业账本事实为唯一证据来源。</p>
 */
public interface CompletionGuard extends AutoCloseable {

    /**
     * 工作流需求配置在业务包内的相对路径。
     */
    String FILE = "workflow-requirements.json";

    /**
     * 报告需求配置在业务包内的相对路径。
     */
    String REPORT_FILE = "report-requirements.json";

    /**
     * 当前 run 的受信资源读取入口，由宿主提供；上传的资源包只能作为数据读取，不能安装代码或自行解释。
     */
    interface Resources {
        /**
         * 读取并解析业务包内的 JSON 资源。
         *
         * @param path 业务包内的相对路径。
         * @return 解析后的 JSON 对象。
         * @throws Exception 资源缺失、越权或格式非法时抛出。
         */
        JSONObject json(String path) throws Exception;

        /**
         * 返回当前 run 的任务输入，默认返回修订号为 0 的空输入。
         *
         * @return 含 {@code inputRevision} 与 {@code inputs} 的输入对象。
         * @throws Exception 读取失败时抛出。
         */
        default JSONObject taskInput() throws Exception {
            return new JSONObject()
                    .put("inputRevision", 0)
                    .put("inputs", new JSONArray());
        }

        /**
         * 读取业务包内资源的原始字节，默认不支持。
         *
         * @param path 业务包内的相对路径。
         * @return 资源字节内容。
         * @throws Exception 默认实现恒抛出不支持操作异常。
         */
        default byte[] bytes(String path) throws Exception {
            throw new UnsupportedOperationException("RESOURCE_BYTES_UNAVAILABLE");
        }
    }

    /**
     * 校验业务包内的需求配置是否合法。
     *
     * @param resources 当前 run 的受信资源读取入口。
     * @throws Exception 配置非法或读取失败时抛出。
     */
    void validate(Resources resources) throws Exception;

    /**
     * 依据宿主已确认的证据检查业务工作流是否闭环。
     *
     * <p>证据只来自宿主的受信 Provider/作业账本，不接受模型自报的执行结果。</p>
     *
     * @param resources 当前 run 的受信资源读取入口。
     * @param evidence  宿主已确认的证据列表。
     * @return 含满足标记、缺口与修复上限的检查结论。
     * @throws Exception 证据或配置非法时抛出。
     */
    JSONObject check(Resources resources, JSONArray evidence) throws Exception;

    /**
     * 推进可选的固定工作流，返回宿主提案或终态，绝不返回模型决策。
     *
     * @param resources 当前 run 的受信资源读取入口。
     * @param evidence  宿主已确认的证据列表。
     * @param skill     当前技能名。
     * @return 宿主提案或终态；不支持固定流程时返回 {@code null}。
     * @throws Exception 证据或配置非法时抛出。
     */
    default JSONObject advance(Resources resources, JSONArray evidence, String skill) throws Exception {
        return null;
    }

    /**
     * 是否支持按固定交互流程推进，默认不支持。
     *
     * @return 支持时返回 {@code true}。
     */
    default boolean supportsInteraction() {
        return false;
    }

    /**
     * 是否支持报告校验，默认不支持。
     *
     * @return 支持时返回 {@code true}。
     */
    default boolean supportsReports() {
        return false;
    }

    /**
     * 校验报告需求配置，默认拒绝。
     *
     * @param resources 当前 run 的受信资源读取入口。
     * @throws Exception 默认实现恒抛出报告校验不可用异常。
     */
    default void validateReport(Resources resources) throws Exception {
        throw new IllegalStateException("REPORT_GUARD_UNAVAILABLE");
    }

    /**
     * 结合模型决策对工作流与报告做最终检查，默认退化为只检查工作流。
     *
     * @param resources    当前 run 的受信资源读取入口。
     * @param evidence     宿主已确认的证据列表。
     * @param decision     模型给出的决策。
     * @param skill        当前技能名。
     * @param reportSkills 需要提交报告的技能集合。
     * @return 含满足标记与最终结论的检查结果。
     * @throws Exception 证据或配置非法时抛出。
     */
    default JSONObject checkFinal(Resources resources, JSONArray evidence, JSONObject decision,
                                  String skill, Set<String> reportSkills) throws Exception {
        return check(resources, evidence);
    }

    /**
     * 释放校验器持有的资源，默认无需释放。
     */
    @Override
    default void close() {
    }
}
