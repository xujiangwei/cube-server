/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import org.json.JSONObject;

/**
 * Worker 执行类提供方适配器：只做执行档案声明与认证回执的结果转换，不执行物理作业。
 *
 * <p>关键设计点：
 * <ul>
 *   <li>物理执行归属经认证的专用 Python worker（Firecracker/KVM 由配套 Python 工程的宿主进程启动），
 *       本接口不启动宿主程序、不做本机清理；</li>
 *   <li>{@link #runnerType()} 必须为 {@code sandbox}，与普通 tool 提供方互斥，作业不可跨类型领取；</li>
 *   <li>{@link #complete} 只接受经 Broker 认证的 worker 回执，绝不接受 SKILL 子进程直出的输出。</li>
 * </ul>
 */
public interface WorkerExecutionProvider extends QueryProvider {
    /**
     * 返回本提供方绑定的执行档案标识，须与 worker 登记的执行档案逐字节一致。
     *
     * @return 执行档案标识（如 linux-firecracker-python-v1）
     */
    String executionProfile();

    /**
     * 将经认证的 sandbox worker 回执转换为落库结果，只以持久化、已认证的回执调用，
     * 绝不接受 SKILL 子进程输出。
     *
     * @param invocation 本次调用的可信上下文
     * @param output     回执中经 schema 校验的输出载荷
     * @return 符合 resultSchema 的结果对象，由 Broker 再次校验后落库
     * @throws Exception 结果构造或校验失败时抛出，由 Broker 转为错误码结果
     */
    JSONObject complete(ProviderInvocation invocation, JSONObject output) throws Exception;
}
