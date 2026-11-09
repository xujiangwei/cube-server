package cube.service.harness.store;

import static cube.service.harness.store.JdbcRunStore.*;
import cube.service.harness.runtime.RuntimeSupport;
import org.json.*;

/** Small, generic provider metadata in the existing Harness database. No media bytes. */
public final class ProviderStore {
    private final JdbcRunStore store;
    private final boolean mysql;
    public ProviderStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        this.mysql = mysql;
        // DDL belongs exclusively to StoreBootstrap's versioned, strictly verified definition.
    }
    private void serial(java.sql.Connection c) throws Exception {
        if (mysql) rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1 FOR UPDATE");
    }
    public JSONObject source(String tenant, String ref) {
        String[] parts = ref.split("@", -1);
        if (parts.length != 2) throw new StoreException("CONNECTOR_CONFIG_INVALID");
        return store.tx(c -> {
            JSONObject row = first(rows(c, "SELECT config_json FROM harness_data_source WHERE tenant=? AND source_id=? AND source_version=?", tenant, parts[0], parts[1]));
            if (row == null) throw new StoreException("DATA_SOURCE_FORBIDDEN");
            return new JSONObject(row.getString("config_json"));
        });
    }
    public void authorize(String tenant, String subject, String ref) {
        boolean ok = store.tx(c -> {
            JSONObject row = first(rows(c, "SELECT allowed FROM harness_source_grant WHERE tenant=? AND subject_id=? AND source_ref=?", tenant, subject, ref));
            return row != null && row.getInt("allowed") == 1;
        });
        if (!ok) throw new StoreException("DATA_SOURCE_FORBIDDEN");
    }
    public JSONObject putSource(String tenant, String id, String version, JSONObject value) {
        RuntimeSupport.id(id); RuntimeSupport.id(version);
        return store.tx(c -> {
            serial(c);
            JSONObject old = first(rows(c, "SELECT digest FROM harness_data_source WHERE tenant=? AND source_id=? AND source_version=?", tenant, id, version));
            String digest = hash(value);
            if (old != null && !old.getString("digest").equals(digest)) throw new StoreException("IMMUTABLE_SOURCE_CONFLICT");
            if (old == null) update(c, "INSERT INTO harness_data_source VALUES (?,?,?,?,?)", tenant, id, version, digest, canonical(value));
            return new JSONObject().put("sourceRef", id + "@" + version).put("digest", digest);
        });
    }
    public JSONObject grant(String tenant, String subject, String ref, boolean allowed) {
        RuntimeSupport.id(subject); source(tenant, ref);
        return store.tx(c -> {
            serial(c);
            update(c, "DELETE FROM harness_source_grant WHERE tenant=? AND subject_id=? AND source_ref=?", tenant, subject, ref);
            update(c, "INSERT INTO harness_source_grant VALUES (?,?,?,?)", tenant, subject, ref, allowed ? 1 : 0);
            return new JSONObject().put("sourceRef", ref).put("subject", subject).put("allowed", allowed);
        });
    }
    public JSONObject grantRemote(String tenant, String actor, String key, String requestHash, String subject, String ref, boolean allowed) {
        RuntimeSupport.id(subject); source(tenant, ref);
        return store.tx(c -> {
            serial(c);
            JSONObject prior = first(rows(c, "SELECT request_hash,response_json FROM harness_admin_request WHERE tenant=? AND subject_id=? AND request_key=?", tenant, actor, key));
            if (prior != null) {
                if (!prior.getString("request_hash").equals(requestHash)) throw new StoreException("IDEMPOTENCY_CONFLICT");
                return new JSONObject(prior.getString("response_json"));
            }
            update(c, "DELETE FROM harness_source_grant WHERE tenant=? AND subject_id=? AND source_ref=?", tenant, subject, ref);
            update(c, "INSERT INTO harness_source_grant VALUES (?,?,?,?)", tenant, subject, ref, allowed ? 1 : 0);
            JSONObject result = new JSONObject().put("sourceRef", ref).put("subject", subject).put("allowed", allowed);
            update(c, "INSERT INTO harness_admin_request VALUES (?,?,?,?,?)", tenant, actor, key, requestHash, canonical(result));
            return result;
        });
    }
    /** Caller has already authenticated the administration identity. */
    public JSONObject replay(String tenant, String subject, String key, String requestHash, JSONObject result) {
        return store.tx(c -> {
            serial(c);
            JSONObject old = first(rows(c, "SELECT * FROM harness_admin_request WHERE tenant=? AND subject_id=? AND request_key=?", tenant, subject, key));
            if (old != null) {
                if (!old.getString("request_hash").equals(requestHash)) throw new StoreException("IDEMPOTENCY_CONFLICT");
                return new JSONObject(old.getString("response_json"));
            }
            if (result == null) return null;
            update(c, "INSERT INTO harness_admin_request VALUES (?,?,?,?,?)", tenant, subject, key, requestHash, canonical(result));
            return result;
        });
    }
    private void owner(java.sql.Connection c, JSONObject job) throws Exception {
        JSONObject fresh = first(rows(c, "SELECT cancel_requested FROM harness_tool_job WHERE job_id=?", job.getString("job_id")));
        if (fresh == null || fresh.getInt("cancel_requested") != 0 || first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", job.getString("run_id"))) != null) throw new StoreException("TOOL_CANCELLED");
        JSONObject run = store.run(c, job.getString("tenant"), job.getString("subject_id"), job.getString("run_id"), true);
        store.owner(c, run, new JdbcRunStore.Lease(job.getString("tenant"), job.getString("subject_id"), job.getString("run_id"), run.getString("owner_id"), job.getLong("origin_fence")));
        if (!run.getString("state").equals("running")) throw new StoreException("TOOL_CANCELLED");
    }
    public JSONObject fact(JSONObject job, String kind, String id, JSONObject value, boolean confirmed) {
        return store.tx(c -> {
            if (value != null) owner(c, job);
            JSONObject row = first(rows(c, "SELECT * FROM harness_provider_fact WHERE run_id=? AND fact_kind=? AND fact_id=?", job.getString("run_id"), kind, id));
            if (row == null && value != null) {
                update(c, "INSERT INTO harness_provider_fact VALUES (?,?,?,?,?)", job.getString("run_id"), job.getString("job_id"), kind, id, canonical(value));
                return value;
            }
            if (row == null) throw new StoreException("MEDIA_REF_FORBIDDEN");
            if (value != null && !canonical(value).equals(row.getString("value_json"))) throw new StoreException("PROVIDER_FACT_CONFLICT");
            if (confirmed) {
                JSONObject call = first(rows(c, "SELECT t.status,t.result_json FROM harness_tool_call t JOIN harness_tool_job j ON t.run_id=j.run_id AND t.intent_id=j.call_id AND t.attempt=j.attempt WHERE j.job_id=?", row.getString("job_id")));
                if (call == null || !call.getString("status").equals("confirmed") || !new JSONObject(call.getString("result_json")).optString("status").equals("success")) throw new StoreException("MEDIA_REF_FORBIDDEN");
            }
            return new JSONObject(row.getString("value_json"));
        });
    }
    /** Atomic capacity reservation; unknown executions deliberately keep their slot. */
    public JSONObject reserve(JSONObject job, JSONArray units, String unit, JSONObject request) {
        return store.tx(c -> {
            owner(c, job); serial(c);
            JSONObject old = first(rows(c, "SELECT value_json FROM harness_provider_fact WHERE run_id=? AND fact_kind='execution' AND fact_id=?", job.getString("run_id"), job.getString("job_id")));
            if (old != null) throw new StoreException("CAPABILITY_EXECUTION_ALREADY_STARTED");
            JSONObject selected = null; double load = Double.MAX_VALUE;
            for (int i = 0; i < units.length(); i++) {
                JSONObject candidate = units.getJSONObject(i);
                if (!candidate.getString("unit").equals(unit) || !candidate.getBoolean("ready")) continue;
                if ("SpecializedDetection".equals(unit)) {
                    JSONObject option = request.getJSONObject("option");
                    if (!cube.aigc.SpecializedDetectionCapabilities.ready(candidate)
                            || candidate.optInt("availableImages") <= 0
                            || !cube.aigc.SpecializedDetectionCapabilities.supports(candidate,
                            option.getString("profile"), option.optBoolean("sliced"))) continue;
                }
                JSONArray active = rows(c, "SELECT a.value_json FROM harness_provider_fact a LEFT JOIN harness_provider_fact b ON a.run_id=b.run_id AND a.fact_id=b.fact_id AND b.fact_kind='receipt' WHERE a.fact_kind='execution' AND b.fact_id IS NULL");
                int count = 0;
                for (int j = 0; j < active.length(); j++) if (new JSONObject(active.getJSONObject(j).getString("value_json")).getString("instanceId").equals(candidate.getString("instanceId"))) count++;
                int capacity = candidate.getInt("capacity");
                if (capacity > count && (double) count / capacity < load) { selected = candidate; load = (double) count / capacity; }
            }
            if (selected == null) throw new StoreException("CAPABILITY_NOT_READY");
            JSONObject execution = new JSONObject(request.toString()).put("instanceId", selected.getString("instanceId")).put("requestId", job.getString("job_id")).put("serviceSn", cell.util.Utils.generateSerialNumber());
            update(c, "INSERT INTO harness_provider_fact VALUES (?,?,'execution',?,?)", job.getString("run_id"), job.getString("job_id"), job.getString("job_id"), canonical(execution));
            return execution;
        });
    }
    /** Physical receipts may arrive after cancellation/fence loss, but cannot change the answer. */
    public void receipt(JSONObject job, JSONObject receipt) {
        if (!receipt.optBoolean("actualFinished")) return;
        store.tx(c -> {
            serial(c);
            JSONObject intent = first(rows(c, "SELECT value_json FROM harness_provider_fact WHERE run_id=? AND fact_kind='execution' AND fact_id=?", job.getString("run_id"), job.getString("job_id")));
            if (intent == null) throw new StoreException("CAPABILITY_RECEIPT_FORBIDDEN");
            JSONObject expected = new JSONObject(intent.getString("value_json"));
            if (!expected.getString("instanceId").equals(receipt.getString("instanceId")) || !expected.getString("requestId").equals(receipt.getString("requestId"))) throw new StoreException("CAPABILITY_RECEIPT_FORBIDDEN");
            JSONObject old = first(rows(c, "SELECT value_json FROM harness_provider_fact WHERE run_id=? AND fact_kind='receipt' AND fact_id=?", job.getString("run_id"), job.getString("job_id")));
            if (old == null) update(c, "INSERT INTO harness_provider_fact VALUES (?,?,'receipt',?,?)", job.getString("run_id"), job.getString("job_id"), job.getString("job_id"), canonical(receipt));
            else if (!old.getString("value_json").equals(canonical(receipt))) throw new StoreException("CAPABILITY_RECEIPT_CONFLICT");
            return null;
        });
    }
    public boolean finished(JSONObject job) {
        return store.tx(c -> first(rows(c, "SELECT fact_id FROM harness_provider_fact WHERE run_id=? AND fact_kind='receipt' AND fact_id=?", job.getString("run_id"), job.getString("job_id"))) != null);
    }
}
