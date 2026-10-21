/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.files.FileService;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import cube.service.harness.store.ToolJobStore;
import org.json.JSONObject;

/**
 * 单次工具调用的可信每次调用上下文，仅由 Broker 从持久化 job 创建；身份一律来自持久化记录，
 * 不采用模型供给的身份信息。
 *
 * <p>每个实例绑定一个已授权的 durable job（tenant/subject/run/call/job 五元组），
 * 面向 provider 暴露受控的输入读取与产物写入能力：
 * <ul>
 *   <li>文件访问经 {@link JdbcRunStore.Lease} 续权，租约过期、fence 变化或 run 离开 running 即失败；</li>
 *   <li>未配置文件服务（files 为 null）时所有文件方法以 {@code FILES_DISABLED} 拒绝，不静默放行；</li>
 *   <li>实例非线程安全，生命周期不超出创建它的那一次 provider 调用。</li>
 * </ul>
 */
public final class ProviderInvocation {

    /**
     * 任务所属租户（域）标识，取自 job 的 tenant 字段。
     */
    public final String tenant;

    /**
     * 任务所属联系人标识，取自 job 的 subject_id 字段。
     */
    public final String subject;

    /**
     * 所属 run 标识，取自 job 的 run_id 字段。
     */
    public final String runId;

    /**
     * 所属 call intent 标识，取自 job 的 call_id 字段。
     */
    public final String callId;

    /**
     * 持久化工具作业标识，取自 job 的 job_id 字段，用于取消判定与账本对账。
     */
    public final String jobId;

    /**
     * 原始 job 行快照，含 origin_fence 等仅存于该行的权威字段。
     */
    private final JSONObject job;

    /**
     * 工具作业存储，用于取消判定、吊销检查与当前 run 状态读取。
     */
    private final ToolJobStore jobs;

    /**
     * 文件服务；为 null 表示该部署未启用受控文件访问。
     */
    private final FileService files;

    /**
     * 以持久化 job 行构造调用上下文，仅限同包 Broker 调用。
     *
     * @param job   持久化工具作业行，必须包含 tenant、subject_id、run_id、call_id、job_id 字段
     * @param jobs  工具作业存储，供取消判定与租约续权使用
     * @param files 文件服务，可为 null 表示未启用文件访问
     */
    ProviderInvocation(JSONObject job, ToolJobStore jobs, FileService files) {
        this.job = job;
        this.jobs = jobs;
        this.files = files;
        tenant = job.getString("tenant");
        subject = job.getString("subject_id");
        runId = job.getString("run_id");
        callId = job.getString("call_id");
        jobId = job.getString("job_id");
    }

    /**
     * 判断本次调用是否已被取消或失去继续执行的授权依据。
     *
     * <p>满足任一条件即视为已取消：线程中断标志、job 的取消请求位、job 已被吊销、
     * 当前 run 的 fence 不再是本 job 的原 fence（出现更新一代执行者）、或 run 快照状态
     * 已离开 running。判定为只读观察，不产生任何持久化副作用。
     *
     * @return true 表示调用应放弃或只能做清理，不得宣称新的执行效果
     */
    public boolean cancelled() {
        JSONObject j = jobs.job(jobId), r = jobs.currentRun(job);
        return Thread.currentThread().isInterrupted()
                || j.getInt("cancel_requested") != 0
                || jobs.revoked(j)
                || r.getLong("fence") != job.getLong("origin_fence")
                || !r.getJSONObject("snapshot").getString("state").equals("running");
    }

    /**
     * 为当前调用获取 run 租约，用于后续受控文件访问的授权绑定。
     *
     * @return 绑定本 job 原 fence 与身份的运行租约
     * @throws StoreException 调用已取消或失去授权依据时抛出，错误码为 {@code TOOL_CANCELLED}
     */
    private JdbcRunStore.Lease lease() {
        if (cancelled()) {
            throw new StoreException("TOOL_CANCELLED");
        }
        JSONObject r = jobs.currentRun(job);
        return new JdbcRunStore.Lease(
                tenant, subject, runId, r.getString("owner"), job.getLong("origin_fence"));
    }

    /**
     * 读取本次调用绑定的受控输入文件内容。
     *
     * @param id         输入文件标识，须与 call 的参数引用一致
     * @param maxBytes   允许的最大字节数，超出即拒绝
     * @param mediaTypes 允许的媒体类型集合，用于绑定校验
     * @return 文件内容字节；仅在授权与配额校验通过后返回
     * @throws StoreException 文件服务未启用时抛出，错误码为 {@code FILES_DISABLED}
     * @throws Exception      底层文件读取、授权或配额校验失败时向上传播
     */
    public byte[] input(String id, int maxBytes, java.util.Set<String> mediaTypes) throws Exception {
        if (files == null) {
            throw new StoreException("FILES_DISABLED");
        }
        return files.readBound(lease(), callId, id, maxBytes, mediaTypes);
    }

    /**
     * 以文本内容创建本次调用绑定的受控产物文件。
     *
     * @param fileName 产物文件名，由调用方声明并受文件服务校验
     * @param text     文本内容，按 UTF-8 持久化
     * @return 文件服务返回的产物引用描述（含标识与摘要）
     * @throws StoreException 文件服务未启用时抛出，错误码为 {@code FILES_DISABLED}
     * @throws Exception      底层写入、授权或配额校验失败时向上传播
     */
    public JSONObject textArtifact(String fileName, String text) throws Exception {
        if (files == null) {
            throw new StoreException("FILES_DISABLED");
        }
        return files.writeText(
                lease(), callId, new JSONObject().put("fileName", fileName).put("text", text));
    }
}
