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
 * Trusted per-call context, created only by Broker from the durable job. No model-supplied
 * identity.
 */
public final class ProviderInvocation {

    public final String tenant, subject, runId, callId, jobId;

    private final JSONObject job;

    private final ToolJobStore jobs;

    private final FileService files;

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

    public boolean cancelled() {
        JSONObject j = jobs.job(jobId), r = jobs.currentRun(job);
        return Thread.currentThread().isInterrupted()
                || j.getInt("cancel_requested") != 0
                || jobs.revoked(j)
                || r.getLong("fence") != job.getLong("origin_fence")
                || !r.getJSONObject("snapshot").getString("state").equals("running");
    }

    private JdbcRunStore.Lease lease() {
        if (cancelled()) throw new StoreException("TOOL_CANCELLED");
        JSONObject r = jobs.currentRun(job);
        return new JdbcRunStore.Lease(
                tenant, subject, runId, r.getString("owner"), job.getLong("origin_fence"));
    }

    public byte[] input(String id, int maxBytes, java.util.Set<String> mediaTypes) throws Exception {
        if (files == null) throw new StoreException("FILES_DISABLED");
        return files.readBound(lease(), callId, id, maxBytes, mediaTypes);
    }

    public JSONObject textArtifact(String fileName, String text) throws Exception {
        if (files == null) throw new StoreException("FILES_DISABLED");
        return files.writeText(
                lease(), callId, new JSONObject().put("fileName", fileName).put("text", text));
    }
}
