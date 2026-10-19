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
 * Transport-independent business dispatch. All caller authority is checked here, not in Jetty.
 */
final class HarnessBusiness implements AutoCloseable {

    private final HarnessService service;

    private final HarnessConfig config;

    private final ExecutionRuntime execution;

    final HarnessTransfers transfers;

    HarnessBusiness(HarnessService service, HarnessConfig config, ExecutionRuntime execution) {
        this.service = service;
        this.config = config;
        this.execution = execution;
        this.transfers = execution == null || execution.files == null ? null : new HarnessTransfers(execution.files);
    }

    JSONObject handle(ActionDialect dialect, long transport, BooleanSupplier valid, Function<String, OutputStream> opener) {
        try {
            HarnessAction action = HarnessAction.fromName(dialect.getName());
            if (action == null) throw new Failure(400, "UNKNOWN_ACTION");
            JSONObject data = new Packet(dialect).data;
            String credential = data.optString("credential", "");
            HarnessIdentity authenticated = service.authenticate(credential);
            ProfileRegistry.Identity identity = authenticated.user;
            boolean approver = authenticated.allowed(config.values, "control.approver.subjects");
            if (action == HarnessAction.Trace && !authenticated.allowed(config.values, "trace.reader.subjects"))
                throw new Failure(403, "TRACE_READER_REQUIRED");
            if (action == HarnessAction.DecideApproval && !approver) throw new Failure(403, "APPROVER_FORBIDDEN");
            BooleanSupplier transferValid = () -> valid.getAsBoolean() && System.currentTimeMillis() < authenticated.expires;
            if (!valid.getAsBoolean()) throw new Failure(503, "HARNESS_UNAVAILABLE");
            String id = data.optString("id", ""), approval = data.optString("approvalId", "");
            if ((!id.isEmpty() && !id.matches("[A-Za-z0-9_.:-]{1,128}"))
                    || (!approval.isEmpty() && !approval.matches("[A-Za-z0-9_.:-]{1,128}")))
                throw new Failure(400, "INVALID_RESOURCE_ID");
            String idem = data.optString("key", "");
            if (("POST".equals(action.method) || "PUT".equals(action.method)) && !idem.matches("[A-Za-z0-9_.:-]{1,128}"))
                throw new Failure(400, "IDEMPOTENCY_KEY_REQUIRED");
            byte[] raw = dialect.containsParam("body") ? dialect.getParam("body") : new byte[0];
            if (raw.length > HarnessAction.MAX_BODY_BYTES) throw new Failure(413, "PAYLOAD_TOO_LARGE");
            JSONObject body = null;
            if (action.schema != null) {
                if (!data.optString("contentType", "").toLowerCase(Locale.ROOT).startsWith("application/json"))
                    throw new Failure(415, "UNSUPPORTED_MEDIA_TYPE");
                body = Contract.parse(action.schema, raw);
            }
            if (action == HarnessAction.Capabilities) return reply(200, service.capabilities());
            if (action == HarnessAction.TaskProfiles) return reply(200, execution == null
                    ? new JSONObject().put("profiles", new JSONArray()) : execution.taskProfiles(identity));
            if (execution == null) throw new Failure(503, "HARNESS_NOT_READY");
            switch (action) {
                case TaskProfile:
                    JSONArray profiles = execution.taskProfiles(identity).getJSONArray("profiles");
                    for (int i = 0; i < profiles.length(); i++)
                        if (profiles.getJSONObject(i).getString("profileId").equals(id))
                            return reply(200, profiles.getJSONObject(i));
                    throw new Failure(404, "PROFILE_NOT_FOUND");
                case CreateRun:
                    return reply(202, execution.create(identity, idem, body));
                case ReadRun:
                    return reply(200, execution.read(approver ? execution.owner(identity, id) : identity, id));
                case Events:
                    long[] cursor = cursor(data.optString("query", ""), data.optString("lastEventId", ""));
                    return reply(200, execution.events(identity, id, cursor[0], (int) cursor[1]));
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
            if (execution.files == null) throw new Failure(503, "HARNESS_NOT_READY");
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
                    if (!data.optString("contentType", "").equals("application/octet-stream"))
                        throw new Failure(415, "UNSUPPORTED_MEDIA_TYPE");
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

    static long[] cursor(String query, String last) {
        long after = 0, limit = 100;
        Set<String> seen = new HashSet<>();
        if (!query.isEmpty()) for (String pair : query.split("&", -1)) {
            String[] kv = pair.split("=", -1);
            if (kv.length != 2 || !seen.add(kv[0]) || !kv[1].matches("[0-9]{1,15}"))
                throw new Failure(400, "INVALID_EVENT_CURSOR");
            if (kv[0].equals("after")) after = Long.parseLong(kv[1]);
            else if (kv[0].equals("limit")) limit = Long.parseLong(kv[1]);
            else throw new Failure(400, "INVALID_EVENT_CURSOR");
        }
        if (!last.isEmpty()) {
            if (!last.matches("[0-9]{1,15}") || (seen.contains("after") && after != Long.parseLong(last)))
                throw new Failure(400, "INVALID_EVENT_CURSOR");
            after = Long.parseLong(last);
        }
        if (limit < 1 || limit > 100) throw new Failure(400, "INVALID_EVENT_LIMIT");
        return new long[]{after, limit};
    }

    static JSONObject reply(int status, JSONObject body) {
        return new JSONObject().put("status", status).put("headers", new JSONObject()).put("body", body);
    }

    static JSONObject error(int status, String code) {
        return reply(status, new JSONObject().put("requestId", UUID.randomUUID().toString())
                .put("errorCode", code).put("retryable", status == 429 || status == 503));
    }

    static JSONObject failure(Exception e) {
        if (e instanceof Failure) return error(((Failure) e).status, e.getMessage());
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
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,100}")) code = "INVALID_REQUEST";
            return error(code.equals("PAYLOAD_TOO_LARGE") ? 413 : 400, code);
        }
        return error(500, "INTERNAL_ERROR");
    }

    static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String code) {
            super(code);
            this.status = status;
        }
    }

    @Override
    public void close() {
        if (transfers != null) transfers.close();
    }
}
