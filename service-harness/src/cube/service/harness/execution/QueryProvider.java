/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import org.json.JSONObject;

/**
 * 受信任的、由管理员安装的工具查询提供方扩展，负责对 schema 校验后的工具参数执行实际查询。
 *
 * <p>关键设计点：
 * <ul>
 *   <li>业务 API 地址、后端凭证、结果投影与解释语义全部归属扩展自身；核心只做协议、schema 与授权校验；</li>
 *   <li>扩展以 JVM 权限运行，必须经管理员审核后随部署安装，上传的 SKILL 代码无法安装提供方；</li>
 *   <li>提供方按 operation 注册，一个部署可并存多个提供方；未注册 operation 的调用以
 *       {@code TOOL_PROVIDER_UNAVAILABLE} 拒绝；</li>
 *   <li>所有查询必须幂等可重放：结果先落库再回报，重试不得产生第二次上游效果。</li>
 * </ul>
 */
public interface QueryProvider extends AutoCloseable {
    /**
     * 重启或unknown对账期的清理/探测入口，仅供 Broker 恢复窗口调用。
     *
     * <p>约定：只允许做清理或探测，严禁重复原始上游操作，也不得宣称未被观察到的执行结果。
     * 默认实现不做任何事并返回 false。
     *
     * @param invocation 本次调用的可信上下文，含取消与授权判定
     * @return true 表示提供方已确认无需保留的未决效果，可由 Broker 标记对账完成
     * @throws Exception 清理/探测失败时抛出，由 Broker 记为 unknown 并稍后重试
     */
    default boolean reconcile(ProviderInvocation invocation) throws Exception {
        return false;
    }

    /**
     * 返回本提供方登记的 runner 类型，决定作业由通用 tool runner 还是专用 sandbox worker 执行。
     *
     * @return runner 类型标识，默认 {@code "tool"}；sandbox 类提供方返回 {@code "sandbox"}
     */
    default String runnerType() {
        return "tool";
    }

    /**
     * 返回本提供方的执行是否独立于调用线程（即是否可经恢复队列异步对账）。
     *
     * @return true 表示执行独立，Broker 可异步重试清理而不占用查询线程；默认 false
     */
    default boolean independentExecution() {
        return false;
    }

    /**
     * 携带可信调用上下文执行查询，默认转交给无上下文的 {@link #query(JSONObject)}。
     *
     * <p>需要访问受控输入/产物的提供方应重写本方法，经 invocation 读取文件而非自行拼接路径。
     *
     * @param invocation 本次调用的可信上下文
     * @param arguments  已按 inputSchema 与提供方校验通过的工具参数
     * @return 符合 resultSchema 的结果对象，由 Broker 再次校验后落库
     * @throws Exception 上游访问或结果构造失败时抛出
     */
    default JSONObject query(ProviderInvocation invocation, JSONObject arguments) throws Exception {
        return query(arguments);
    }

    /**
     * 返回本提供方的 operation 标识，是部署端注册与作业路由的唯一键。
     *
     * @return operation 标识，须为合法的短标识格式
     */
    String operation();

    /**
     * 返回本提供方执行所需的权限标识，须与 TaskProfile 声明的工具权限一致。
     *
     * @return 权限标识，派发前与受信身份/TaskProfile 的权限集合取交集校验
     */
    String permission();

    /**
     * 返回工具输入参数的 JSON Schema，与发布包内的声明必须逐字节一致。
     *
     * @return 输入参数 schema 对象
     */
    JSONObject inputSchema();

    /**
     * 返回工具结果的 JSON Schema，用于校验提供方输出后才可落库。
     *
     * @return 结果 schema 对象
     */
    JSONObject resultSchema();

    /**
     * 在 schema 校验之后执行提供方自定义的参数校验（取值域、组合约束等）。
     *
     * @param arguments 已通过 inputSchema 校验的工具参数
     */
    void validate(JSONObject arguments);

    /**
     * 无上下文便捷查询入口；需要受控文件访问的提供方应改用
     * {@link #query(ProviderInvocation, JSONObject)}。
     *
     * @param arguments 已校验的工具参数
     * @return 符合 resultSchema 的结果对象
     * @throws Exception 上游访问或结果构造失败时抛出
     */
    JSONObject query(JSONObject arguments) throws Exception;

    /**
     * 对提供方返回的结果执行自定义校验（投影范围、字段一致性等），在 schema 校验之后调用。
     *
     * @param arguments 本次调用的工具参数，用于按参数校验结果
     * @param result    提供方返回、尚未落库的结果对象
     */
    void validateResult(JSONObject arguments, JSONObject result);

    /**
     * 释放提供方持有的上游连接与资源；重复调用必须安全。
     */
    @Override
    void close();
}
