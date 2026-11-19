/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.aigc.completion.MediaProvider;
import cube.service.harness.files.FileService;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.ResourceTools;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.ProviderStore;
import cube.service.harness.store.StoreException;
import cube.service.harness.store.ToolJobStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
     * 资源读取工具，用于按技能读取受控业务资源。
     */
    private ResourceTools resources;

    /**
     * 提供方元数据存储，用于事实记录、授权与账本对账。
     */
    private ProviderStore metadata;

    /**
     * 调用方持有的权限集合，用于构造受信身份。
     */
    private Set<String> permissions;

    /**
     * 当前部署的媒体能力提供方工厂；为 null 表示未启用媒体能力。
     */
    private Supplier<MediaProvider> media;

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
     * 注入运行期依赖，仅限同包 Broker 在构造后调用。
     *
     * @param resources   资源读取工具。
     * @param metadata    提供方元数据存储。
     * @param permissions 调用方持有的权限集合。
     * @param media       媒体能力提供方工厂，可为 null。
     * @return 本实例，便于链式调用。
     */
    ProviderInvocation services(ResourceTools resources,
                                ProviderStore metadata, Set<String> permissions,
                                Supplier<MediaProvider> media) {
        this.resources = resources;
        this.metadata = metadata;
        this.permissions = permissions;
        this.media = media;
        return this;
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
        JSONObject j = jobs.job(jobId);
        JSONObject r = jobs.currentRun(job);
        return Thread.currentThread().isInterrupted()
                || j.getInt("cancel_requested") != 0
                || jobs.revoked(j)
                || r.getLong("fence") != job.getLong("origin_fence")
                || !r.getJSONObject("snapshot").getString("state").equals("running")
                || jobs.remainingMillis(job) == 0;
    }

    /**
     * 读取本次调用绑定的受控业务资源。
     *
     * @param skill 技能名。
     * @param path  业务包内相对路径。
     * @return 解析后的资源 JSON。
     * @throws Exception 调用已取消、上下文未配置或资源读取失败时抛出。
     */
    public JSONObject resource(String skill, String path) throws Exception {
        active();
        if (resources == null) {
            throw new StoreException("PROVIDER_CONTEXT_DISABLED");
        }
        return resources.providerResource(new ProfileRegistry.Identity(tenant, subject, permissions), runId, skill, path);
    }

    /**
     * 授权并读取指定数据源。
     *
     * @param ref 数据源引用。
     * @return 数据源定义。
     */
    public JSONObject source(String ref) {
        active();
        metadata().authorize(tenant, subject, ref);
        return metadata().source(tenant, ref);
    }

    /**
     * 记录一个未确认的事实，供后续调用引用。
     *
     * @param kind  事实类型。
     * @param id    事实标识。
     * @param value 事实内容。
     * @return 记录后的事实对象。
     */
    public JSONObject remember(String kind, String id, JSONObject value) {
        active();
        return metadata().fact(job, kind, id, value, false);
    }

    /**
     * 读取一个已由宿主确认的事实。
     *
     * @param kind 事实类型。
     * @param id   事实标识。
     * @return 已确认的事实对象。
     */
    public JSONObject confirmed(String kind, String id) {
        active();
        return metadata().fact(job, kind, id, null, true);
    }

    /**
     * 返回原始用户输入及其持久接收时间，绝不来自模型。
     */
    public JSONObject taskInput() {
        active();
        return metadata().taskInput(tenant, subject, runId);
    }

    /**
     * 为媒体能力调用预留受控输入。
     *
     * @param unit    媒体单元标识。
     * @param request 预留请求。
     * @return 预留结果。
     */
    public JSONObject reserveMedia(String unit, JSONObject request) {
        active();
        MediaProvider bridge = media == null ? null : media.get();
        if (bridge == null) {
            throw new StoreException("CAPABILITY_NOT_READY");
        }
        return metadata().reserve(job, bridge.mediaUnits(), unit, request);
    }

    /**
     * 返回只读的媒体单元状态快照，提供方只能投影逻辑业务能力。
     *
     * @return 媒体单元数组；能力不可用时返回空数组。
     */
    public JSONArray mediaUnits() {
        active();
        MediaProvider bridge = media == null ? null : media.get();
        return bridge == null ? new JSONArray() : new JSONArray(bridge.mediaUnits().toString());
    }

    /**
     * 返回本次调用距预算耗尽的剩余毫秒数。
     *
     * @return 剩余毫秒数。
     */
    public long remainingMillis() {
        return jobs.providerRemainingMillis(job);
    }

    /**
     * 记录一个媒体阶段事件。
     *
     * @param name    阶段名。
     * @param details 阶段详情。
     */
    public void phase(String name, JSONObject details) {
        remember("media-phase", jobId + ":" + name,
                new JSONObject(details.toString())
                        .put("phase", name)
                        .put("recordedAt", System.currentTimeMillis()));
    }

    /**
     * 执行媒体能力调用，并把回执写入账本后通知监听方。
     *
     * @param execution 调用参数。
     * @param image     图像字节，可为 null。
     * @param listener  回执监听器。
     */
    public void invokeMedia(JSONObject execution, byte[] image, Consumer<JSONObject> listener) {
        MediaProvider bridge = media == null ? null : media.get();
        boolean stopped = cancelled() || remainingMillis() == 0;
        if (stopped || bridge == null) {
            unsent(execution);
            throw new StoreException(stopped ? "TOOL_CANCELLED" : "CAPABILITY_NOT_READY");
        }
        try {
            phase("handoff", new JSONObject()
                    .put("requestId", execution.getString("requestId"))
                    .put("instanceId", execution.getString("instanceId")));
        } catch (RuntimeException failure) {
            unsent(execution);
            throw failure;
        }
        try {
            bridge.invokeMedia(execution, image, receipt -> {
                if (!receipt.optBoolean("actualFinished")) {
                    if ("physical-dispatch".equals(receipt.optString("stage"))) {
                        metadata().mediaDispatch(job, receipt);
                    }
                    return;
                }
                metadata().receipt(job, receipt);
                listener.accept(receipt);
            });
        } catch (RejectedExecutionException rejected) {
            unsent(execution);
            throw new StoreException("CAPABILITY_NOT_READY");
        }
    }

    /**
     * 判断媒体能力是否已产出最终结果。
     *
     * @return 已完成返回 true。
     */
    public boolean mediaFinished() {
        return metadata().finished(job);
    }

    /**
     * 取消本次调用的媒体执行。
     */
    public void cancelMedia() {
        MediaProvider bridge = media == null ? null : media.get();
        if (bridge != null) {
            bridge.cancelMedia(jobId);
        }
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
    public byte[] input(String id, int maxBytes, Set<String> mediaTypes) throws Exception {
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
        return files.writeProviderText(
                lease(), jobId, new JSONObject()
                        .put("fileName", fileName)
                        .put("text", text));
    }

    /**
     * 执行文件交换协议（read/begin/write/finish），仅限同包 Broker 调用。
     *
     * @param provider  文件处理提供方。
     * @param arguments 工具参数。
     * @param request   交换请求。
     * @return 交换结果。
     * @throws Exception 授权、配额或文件读写失败时抛出。
     */
    JSONObject fileExchange(FileProcessingProvider provider, JSONObject arguments, JSONObject request) throws Exception {
        if (files == null) {
            throw new StoreException("FILES_DISABLED");
        }
        JdbcRunStore.Lease current = lease();
        String action = request.getString("operation");
        if (action.equals("read")) {
            List<String> ids = provider.inputIds(arguments);
            int index = request.optInt("inputIndex", 0);
            if (index < 0 || index >= ids.size()) {
                throw new StoreException("FILE_INPUT_INDEX_INVALID");
            }
            return files.readProviderChunk(current, jobId, ids.get(index),
                    provider.inputMediaTypes(arguments, index), request.getLong("offset"));
        }
        if (action.equals("begin")) {
            return files.beginProviderArtifact(current, jobId,
                    provider.outputMetadata(arguments, request.getLong("size"), request.getString("sha256")));
        }
        if (action.equals("write")) {
            return files.writeProviderChunk(current, jobId,
                    request.getLong("offset"), Base64.getDecoder().decode(request.getString("data")));
        }
        if (action.equals("finish")) {
            return files.finishProviderArtifact(current, jobId);
        }
        throw new StoreException("FILE_OPERATION_INVALID");
    }

    /**
     * 返回本次调用绑定的二进制产物引用。
     *
     * @return 产物引用描述。
     */
    public JSONObject binaryArtifact() {
        if (files == null) {
            throw new StoreException("FILES_DISABLED");
        }
        return files.providerArtifact(lease(), jobId);
    }

    /**
     * 返回本次调用绑定的二进制产物文本预览。
     *
     * @param maxChars 最大返回字符数。
     * @return 文本预览。
     * @throws Exception 授权或读取失败时抛出。
     */
    public JSONObject binaryArtifactText(int maxChars) throws Exception {
        if (files == null) {
            throw new StoreException("FILES_DISABLED");
        }
        return files.providerArtifactText(lease(), jobId, maxChars);
    }

    /**
     * 确保本次调用仍处于可执行状态，否则拒绝。
     */
    private void active() {
        if (cancelled()) {
            throw new StoreException("TOOL_CANCELLED");
        }
    }

    /**
     * 返回已配置的提供方元数据存储，未配置则拒绝。
     *
     * @return 提供方元数据存储。
     */
    private ProviderStore metadata() {
        if (metadata == null) {
            throw new StoreException("PROVIDER_CONTEXT_DISABLED");
        }
        return metadata;
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
     * 为未发出即失败的媒体调用记录一条否定回执。
     *
     * @param execution 调用参数。
     */
    private void unsent(JSONObject execution) {
        metadata().receipt(job, new JSONObject()
                .put("requestId", execution.getString("requestId"))
                .put("instanceId", execution.getString("instanceId"))
                .put("actualFinished", true)
                .put("success", false)
                .put("unsent", true));
    }
}
