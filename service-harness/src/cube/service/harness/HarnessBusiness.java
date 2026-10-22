/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.service.harness.execution.ExecutionRuntime;
import cube.service.harness.protocol.Contract;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 传输无关的业务分发：一次 ActionDialect 请求的鉴权、参数校验、路由与状态码全部在此完成，
 * HTTP/Jetty 与 dispatcher 层不做任何权限判断。
 * <p>身份经 Cube Auth 取得后，按动作核对审批/trace 白名单；文件字节相关的动作
 * （上传/下载/开始下载/传输结果/中止）委托 {@link HarnessTransfers}，经同连接 SHM 流完成，
 * 文件字节不进入 ActionDialect。所有异常在本类内映射为统一错误响应，不向传输层抛出。
 */
final class HarnessBusiness implements AutoCloseable {

    /**
     * 所属服务，提供 capabilities 快照与认证入口。
     */
    private final HarnessService service;

    /**
     * 私有配置，提供审批/trace 白名单等键值。
     */
    private final HarnessConfig config;

    /**
     * 执行运行时；未启用执行时为 null，仅 capabilities 与 task-profiles 可用。
     */
    private final ExecutionRuntime execution;

    /**
     * 文件传输协调器；execution 或文件能力缺失时为 null。
     */
    final HarnessTransfers transfers;

    /**
     * 绑定服务、配置与执行运行时，并按文件能力可用性创建传输协调器。
     *
     * @param service   所属 HarnessService
     * @param config    私有配置
     * @param execution 执行运行时；为 null 或其 files 为 null 时不建传输协调器
     */
    HarnessBusiness(HarnessService service, HarnessConfig config, ExecutionRuntime execution) {
        this.service = service;
        this.config = config;
        this.execution = execution;
        this.transfers = execution == null || execution.files == null ? null : new HarnessTransfers(execution.files);
    }

    /**
     * 业务分发入口：鉴权、校验与路由一次完成。
     * <p>流程：动作解析 → Bearer 认证 → 白名单校验 → 会话/租约有效性 →
     * 资源 ID 与幂等键格式 → 原始 JSON body 解析 → capabilities/task-profiles 直回 →
     * 执行态动作 → 文件态动作。文件动作按 transport 与 credential 绑定传输会话。
     *
     * @param dialect   dispatcher 经 SHM 送达的请求，携带动作名与 JSON data
     * @param transport Cell transport 会话标识，传输类动作与之绑定
     * @param valid     会话有效性探针；传输期间持续校验，失效即中止
     * @param opener    SHM 流打开器，供 StartDownload 写回字节流
     * @return 统一响应结构 {@code {status,headers,body}}；异常也映射为错误响应，不向外抛出
     * @throws HarnessBusiness.Failure 400/UNKNOWN_ACTION（动作未知）、403/TRACE_READER_REQUIRED、
     *                                 403/APPROVER_FORBIDDEN、503/HARNESS_UNAVAILABLE（会话已失效）、
     *                                 503/HARNESS_NOT_READY（未启用执行/文件）、400/INVALID_RESOURCE_ID、
     *                                 400/IDEMPOTENCY_KEY_REQUIRED、413/PAYLOAD_TOO_LARGE、415/UNSUPPORTED_MEDIA_TYPE、
     *                                 404/PROFILE_NOT_FOUND；上述异常在本方法内被捕获并转换为错误响应
     */
    JSONObject handle(ActionDialect dialect, long transport, BooleanSupplier valid, Function<String, OutputStream> opener) {
        try {
            HarnessAction action = HarnessAction.fromName(dialect.getName());
            if (action == null) {
                throw new Failure(400, "UNKNOWN_ACTION");
            }
            JSONObject data = new Packet(dialect).data;
            String credential = data.optString("credential", "");
            HarnessIdentity authenticated = service.authenticate(credential);
            ProfileRegistry.Identity identity = authenticated.user;
            boolean approver = authenticated.allowed(config.values, "control.approver.subjects");
            if (action == HarnessAction.Trace && !authenticated.allowed(config.values, "trace.reader.subjects")) {
                throw new Failure(403, "TRACE_READER_REQUIRED");
            }
            if (action == HarnessAction.DecideApproval && !approver) {
                throw new Failure(403, "APPROVER_FORBIDDEN");
            }
            // 传输会话有效性 = 传输连接有效 且 令牌未到期。
            BooleanSupplier transferValid = () -> valid.getAsBoolean() && System.currentTimeMillis() < authenticated.expires;
            if (!valid.getAsBoolean()) {
                throw new Failure(503, "HARNESS_UNAVAILABLE");
            }
            String id = data.optString("id", "");
            String approval = data.optString("approvalId", "");
            if ((!id.isEmpty() && !id.matches("[A-Za-z0-9_.:-]{1,128}"))
                    || (!approval.isEmpty() && !approval.matches("[A-Za-z0-9_.:-]{1,128}"))) {
                throw new Failure(400, "INVALID_RESOURCE_ID");
            }
            String idem = data.optString("key", "");
            if (("POST".equals(action.method) || "PUT".equals(action.method)) && !idem.matches("[A-Za-z0-9_.:-]{1,128}")) {
                throw new Failure(400, "IDEMPOTENCY_KEY_REQUIRED");
            }
            byte[] raw = dialect.containsParam("body") ? dialect.getParam("body") : new byte[0];
            if (raw.length > HarnessAction.MAX_BODY_BYTES) {
                throw new Failure(413, "PAYLOAD_TOO_LARGE");
            }
            JSONObject body = null;
            if (action.schema != null) {
                if (!data.optString("contentType", "").toLowerCase(Locale.ROOT).startsWith("application/json")) {
                    throw new Failure(415, "UNSUPPORTED_MEDIA_TYPE");
                }
                body = Contract.parse(action.schema, raw);
            }
            if (execution != null) {
                execution.ensureTenant(identity);
            }
            if (action == HarnessAction.Capabilities) {
                return reply(200, service.capabilities());
            }
            if (action == HarnessAction.TaskProfiles) {
                return reply(200, execution == null
                        ? new JSONObject().put("profiles", new JSONArray()) : execution.taskProfiles(identity));
            }
            if (execution == null) {
                throw new Failure(503, "HARNESS_NOT_READY");
            }
            switch (action) {
                case TaskProfile:
                    JSONArray profiles = execution.taskProfiles(identity).getJSONArray("profiles");
                    for (int i = 0; i < profiles.length(); i++) {
                        if (profiles.getJSONObject(i).getString("profileId").equals(id)) {
                            return reply(200, profiles.getJSONObject(i));
                        }
                    }
                    throw new Failure(404, "PROFILE_NOT_FOUND");
                case CreateRun:
                    return reply(202, execution.create(identity, idem, body));
                case ReadRun:
                    return reply(200, execution.read(approver ? execution.owner(identity, id) : identity, id));
                case Events:
                    long[] cursor = cursor(data.optString("query", ""), data.optString("lastEventId", ""));
                    return reply(200, execution.events(identity, id, cursor[0], (int) cursor[1]));
                // CancelRun / ResumeRun / DecideApproval 共用控制入口，仅 operation 与 wait 语义不同。
                case CancelRun:
                case ResumeRun:
                case DecideApproval:
                    String operation = action == HarnessAction.CancelRun ? "cancel" : action == HarnessAction.ResumeRun ? "resume" : "approval";
                    String wait = action == HarnessAction.DecideApproval ? approval : action == HarnessAction.ResumeRun ? body.getString("waitId") : "";
                    return reply(202, execution.control(action == HarnessAction.DecideApproval ? execution.owner(identity, id) : identity,
                            id, idem, operation, wait, body, identity.subject, action == HarnessAction.DecideApproval));
                case Feedback:
                    return reply(202, execution.traces.feedback(identity.tenant, identity.subject, id, idem, body));
                case Trace:
                    ProfileRegistry.Identity owner = execution.owner(identity, id);
                    return reply(200, execution.traces.export(owner.tenant, owner.subject, id));
                default:
                    break;
            }
            if (execution.files == null) {
                throw new Failure(503, "HARNESS_NOT_READY");
            }
            switch (action) {
                case CreateInput:
                    return reply(201, execution.files.create(identity, idem, body));
                case ReadInput:
                    return reply(200, execution.files.metadata(identity, id, "input"));
                case ReadArtifact:
                    return reply(200, execution.files.metadata(identity, id, "artifact"));
                case CompleteInput:
                    return reply(200, execution.files.complete(identity, id, body.getString("sha256")));
                case Artifacts:
                    return reply(200, new JSONObject().put("artifacts", execution.files.artifacts(identity, id)));
                case DownloadHandle:
                    return reply(200, execution.files.handle(identity, id));
                case UploadInput:
                    if (!data.optString("contentType", "").equals("application/octet-stream")) {
                        throw new Failure(415, "UNSUPPORTED_MEDIA_TYPE");
                    }
                    return reply(200, transfers.prepare(identity, id, transport, credential, true, null, transferValid));
                case DownloadArtifact:
                    return reply(200, transfers.prepare(identity, id, transport, credential, false, data.optString("query", ""), transferValid));
                case StartDownload:
                    transfers.startDownload(data.getString("transferId"), transport, credential, opener);
                    return reply(202, new JSONObject());
                case TransferResult:
                    return reply(200, transfers.result(data.getString("transferId"), transport, credential));
                case AbortTransfer:
                    transfers.abort(data.getString("transferId"), transport, credential);
                    return reply(200, new JSONObject());
                default:
                    throw new Failure(400, "UNKNOWN_ACTION");
            }
        } catch (Exception e) {
            return failure(e);
        }
    }

    /**
     * 解析 events 游标：query 中 {@code after}/{@code limit} 键值对，并兼容 lastEventId 参数。
     * limit 限定 1–100，默认 100；after 默认 0；键重复、值格式非法、
     * 或 lastEventId 与 after 不一致即拒绝。
     *
     * @param query 原始 query 字符串，{@code &} 分隔的 k=v 对
     * @param last  lastEventId 参数，作为 after 的兼容写法
     * @return 长度为 2 的数组：[0] after（事件序号下限），[1] limit（单页上限）
     * @throws HarnessBusiness.Failure 400/INVALID_EVENT_CURSOR 或 400/INVALID_EVENT_LIMIT
     */
    static long[] cursor(String query, String last) {
        long after = 0;
        long limit = 100;
        Set<String> seen = new HashSet<>();
        if (!query.isEmpty()) {
            for (String pair : query.split("&", -1)) {
                String[] kv = pair.split("=", -1);
                if (kv.length != 2 || !seen.add(kv[0]) || !kv[1].matches("[0-9]{1,15}")) {
                    throw new Failure(400, "INVALID_EVENT_CURSOR");
                }
                if (kv[0].equals("after")) {
                    after = Long.parseLong(kv[1]);
                } else if (kv[0].equals("limit")) {
                    limit = Long.parseLong(kv[1]);
                } else {
                    throw new Failure(400, "INVALID_EVENT_CURSOR");
                }
            }
        }
        if (!last.isEmpty()) {
            if (!last.matches("[0-9]{1,15}") || (seen.contains("after") && after != Long.parseLong(last))) {
                throw new Failure(400, "INVALID_EVENT_CURSOR");
            }
            after = Long.parseLong(last);
        }
        if (limit < 1 || limit > 100) {
            throw new Failure(400, "INVALID_EVENT_LIMIT");
        }
        return new long[]{after, limit};
    }

    /**
     * 组装 dispatcher 业务响应信封。
     *
     * @param status HTTP 状态码
     * @param body   响应体 JSON
     * @return {@code {status,headers,body}} 结构，headers 初始为空对象
     */
    static JSONObject reply(int status, JSONObject body) {
        return new JSONObject().put("status", status).put("headers", new JSONObject()).put("body", body);
    }

    /**
     * 组装带随机 requestId 的错误响应；429 与 503 标记为可重试。
     *
     * @param status HTTP 状态码
     * @param code   对外错误码
     * @return 错误响应信封，body 含 requestId、errorCode 与 retryable
     */
    static JSONObject error(int status, String code) {
        return reply(status, new JSONObject().put("requestId", UUID.randomUUID().toString())
                .put("errorCode", code).put("retryable", status == 429 || status == 503));
    }

    /**
     * 把处理过程中捕获的异常映射为错误响应，永不向传输层抛出：
     * <ul>
     * <li>{@link Failure}：按自身 status 与消息错误码返回</li>
     * <li>StoreException：按错误码映射——NOT_FOUND→404、FORBIDDEN→403、
     * FILE_EXPIRED/WAIT_EXPIRED→410、HARNESS_BUSY/FILE_BUSY/FILE_QUOTA_EXCEEDED→429、
     * 含 CONFLICT 或 EVENT_GAP/RUN_TERMINAL/APPROVAL_ARGUMENTS_CHANGED→409、
     * STORE_ 前缀或 HARNESS_DRAINING→503、其余→400</li>
     * <li>IllegalArgumentException：消息作为错误码（须为大写错误码形状，否则 INVALID_REQUEST）；
     * PAYLOAD_TOO_LARGE→413，其余→400</li>
     * <li>其他异常：500/INTERNAL_ERROR</li>
     * </ul>
     *
     * @param e 处理过程中捕获的异常
     * @return 映射后的错误响应信封
     */
    static JSONObject failure(Exception e) {
        if (e instanceof Failure) {
            return error(((Failure) e).status, e.getMessage());
        }
        if (e instanceof StoreException) {
            String code = ((StoreException) e).code;
            int status = code.endsWith("NOT_FOUND") ? 404 : code.endsWith("FORBIDDEN") ? 403
                    : code.equals("FILE_EXPIRED") || code.equals("WAIT_EXPIRED") ? 410
                    : code.equals("HARNESS_BUSY") || code.equals("FILE_BUSY") || code.equals("FILE_QUOTA_EXCEEDED") ? 429
                    : code.contains("CONFLICT") || code.equals("EVENT_GAP") || code.equals("RUN_TERMINAL") || code.equals("APPROVAL_ARGUMENTS_CHANGED") ? 409
                    : code.startsWith("STORE_") || code.equals("HARNESS_DRAINING") ? 503 : 400;
            return error(status, code);
        }
        if (e instanceof IllegalArgumentException) {
            String code = e.getMessage();
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,100}")) {
                code = "INVALID_REQUEST";
            }
            return error(code.equals("PAYLOAD_TOO_LARGE") ? 413 : 400, code);
        }
        return error(500, "INTERNAL_ERROR");
    }

    /**
     * 携带 HTTP 状态码的业务拒绝；异常消息即对外错误码。
     */
    static final class Failure extends RuntimeException {
        /**
         * 对外 HTTP 状态码，如 400/401/403/404/405/413/415/429/503。
         */
        final int status;

        /**
         * @param status 对外 HTTP 状态码
         * @param code   对外错误码，同时作为异常消息
         */
        Failure(int status, String code) {
            super(code);
            this.status = status;
        }
    }

    /**
     * 关闭业务侧资源：释放全部在途文件传输（流与信号量许可）。
     */
    @Override
    public void close() {
        if (transfers != null) {
            transfers.close();
        }
    }
}
