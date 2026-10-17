/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * N04/N05 metadata shares the RunStore transaction and tenant/owner boundary.
 */
public final class CatalogStore {
    private final JdbcRunStore store;
    private final boolean mysql;

    public CatalogStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        this.mysql = mysql;
    }

    private String lock() {
        return mysql ? " FOR UPDATE" : "";
    }

    public interface Resolver {
        JSONObject resolve(Connection connection) throws Exception;
    }

    public interface WorkspaceAction<T> {
        T apply(JSONObject workspace) throws Exception;
    }

    public JSONObject putRelease(String tenant, String digest, JSONObject manifest, JSONObject provenance) {
        return store.tx(c -> {
            JSONObject old = release(c, tenant, digest, false);
            if (old != null) return old;
            JSONArray dependencies = provenance.getJSONArray("dependencies");
            for (int i = 0; i < dependencies.length(); i++)
                if (!release(c, tenant, dependencies.getString(i), true).getString("state").equals("ready"))
                    throw new StoreException("DEPENDENCY_NOT_READY");
            update(c, "INSERT INTO harness_release VALUES (?,?,?,?,?,'ready',1)", id(tenant), digest, manifest.getString("name"), canonical(manifest), canonical(provenance));
            return release(c, tenant, digest, true);
        });
    }

    public JSONObject release(Connection c, String tenant, String digest, boolean required) throws SQLException {
        JSONObject row = first(rows(c, "SELECT * FROM harness_release WHERE tenant=? AND digest=?" + lock(), id(tenant), RuntimeSupport.digest(digest)));
        if (row == null && required) throw new StoreException("RELEASE_NOT_FOUND");
        return row;
    }

    public JSONObject release(String tenant, String digest) {
        return store.tx(c -> release(c, tenant, digest, true));
    }

    public JSONArray releases(String tenant) {
        return store.tx(c -> rows(c, "SELECT digest,skill_name,state,version FROM harness_release WHERE tenant=? ORDER BY skill_name,digest", id(tenant)));
    }

    public JSONObject lifecycle(String tenant, String digest, long expected, String state) {
        if (!Arrays.asList("ready", "draining", "retired").contains(state))
            throw new StoreException("INVALID_RELEASE_STATE");
        return store.tx(c -> {
            JSONObject old = release(c, tenant, digest, true);
            if (old.getLong("version") != expected) throw new StoreException("VERSION_CONFLICT");
            if (old.getString("state").equals("retired")) throw new StoreException("RELEASE_RETIRED");
            if (state.equals("retired")) {
                JSONArray dependents = rows(c, "SELECT provenance_json FROM harness_release WHERE tenant=? AND state<>'retired'", tenant);
                for (int i = 0; i < dependents.length(); i++) {
                    JSONObject provenance = new JSONObject(dependents.getJSONObject(i).getString("provenance_json"));
                    if (RuntimeSupport.strings(provenance.getJSONArray("dependencies")).contains(digest))
                        throw new StoreException("RELEASE_REFERENCED");
                }
                if (first(rows(c, "SELECT run_id FROM harness_release_ref WHERE tenant=? AND digest=?", tenant, digest)) != null
                        || first(rows(c, "SELECT target_ref FROM harness_binding WHERE tenant=? AND binding_kind='skill' AND target_ref=?", tenant, digest)) != null)
                    throw new StoreException("RELEASE_REFERENCED");
            }
            if (update(c, "UPDATE harness_release SET state=?,version=version+1 WHERE tenant=? AND digest=? AND version=?", state, tenant, digest, expected) != 1)
                throw new StoreException("VERSION_CONFLICT");
            return release(c, tenant, digest, true);
        });
    }

    public JSONObject bind(String tenant, String kind, String name, String target, long expected) {
        if (!Arrays.asList("skill", "task").contains(kind)) throw new StoreException("INVALID_BINDING_KIND");
        return store.tx(c -> {
            if (kind.equals("skill")) {
                JSONObject release = release(c, tenant, target, true);
                if (!release.getString("skill_name").equals(name) || !release.getString("state").equals("ready"))
                    throw new StoreException("RELEASE_NOT_READY");
            } else {
                String[] ref = target.split("@", -1);
                if (ref.length != 2 || !ref[0].equals(name)) throw new StoreException("INVALID_PROFILE_REF");
                profile(c, tenant, "task", ref[0], ref[1]);
            }
            JSONObject old = first(rows(c, "SELECT * FROM harness_binding WHERE tenant=? AND binding_kind=? AND binding_name=?" + lock(), id(tenant), kind, name));
            long v = old == null ? 0 : old.getLong("version");
            if (v != expected) throw new StoreException("VERSION_CONFLICT");
            if (old == null) update(c, "INSERT INTO harness_binding VALUES (?,?,?,?,1)", tenant, kind, name, target);
            else if (update(c, "UPDATE harness_binding SET target_ref=?,version=version+1 WHERE tenant=? AND binding_kind=? AND binding_name=? AND version=?", target, tenant, kind, name, expected) != 1)
                throw new StoreException("VERSION_CONFLICT");
            return new JSONObject().put("target", target).put("version", v + 1);
        });
    }

    public JSONObject active(Connection c, String tenant, String kind, String name) throws SQLException {
        JSONObject row = first(rows(c, "SELECT target_ref,version FROM harness_binding WHERE tenant=? AND binding_kind=? AND binding_name=?" + lock(), id(tenant), kind, name));
        if (row == null) throw new StoreException("BINDING_NOT_FOUND");
        return row;
    }

    public JSONObject putProfile(String tenant, JSONObject definition) {
        String kind = definition.getString("kind"), name = id(definition.getString("id")), version = id(definition.getString("version")), digest = hash(definition);
        return store.tx(c -> {
            JSONObject old = first(rows(c, "SELECT digest FROM harness_profile WHERE tenant=? AND profile_kind=? AND profile_id=? AND profile_version=?" + lock(), id(tenant), kind, name, version));
            if (old != null) {
                if (!old.getString("digest").equals(digest)) throw new StoreException("IMMUTABLE_PROFILE_CONFLICT");
                return definition;
            }
            update(c, "INSERT INTO harness_profile VALUES (?,?,?,?,?,?)", tenant, kind, name, version, digest, canonical(definition));
            return definition;
        });
    }

    public JSONObject profile(Connection c, String tenant, String kind, String name, String version) throws SQLException {
        JSONObject row = first(rows(c, "SELECT definition_json FROM harness_profile WHERE tenant=? AND profile_kind=? AND profile_id=? AND profile_version=?" + lock(), id(tenant), id(kind), id(name), id(version)));
        if (row == null) throw new StoreException("PROFILE_NOT_FOUND");
        return new JSONObject(row.getString("definition_json"));
    }

    public JSONObject profile(String tenant, String kind, String name, String version) {
        return store.tx(c -> profile(c, tenant, kind, name, version));
    }

    public JSONObject createRun(String tenant, String subject, String key, String taskId, JSONObject request, Resolver resolver) {
        return store.createResolved(tenant, subject, key, taskId, request, resolver::resolve);
    }

    public JSONObject pinned(Connection c, String tenant, String subject, String runId) throws SQLException {
        store.run(c, tenant, subject, runId, true);
        JSONObject row = first(rows(c, "SELECT config_json,digest FROM harness_run_config WHERE tenant=? AND subject_id=? AND run_id=?", tenant, subject, runId));
        if (row == null) throw new StoreException("RUN_CONFIG_NOT_PINNED");
        JSONObject value = new JSONObject(row.getString("config_json"));
        if (!hash(value).equals(row.getString("digest"))) throw new StoreException("RUN_CONFIG_CORRUPT");
        return value;
    }

    public JSONObject pinned(String tenant, String subject, String runId) {
        return store.tx(c -> pinned(c, tenant, subject, runId));
    }

    public JSONArray activeSkills(String tenant, String subject, String runId) {
        return store.tx(c -> {
            pinned(c, tenant, subject, runId);
            return rows(c, "SELECT digest FROM harness_run_skill WHERE run_id=? ORDER BY digest", runId);
        });
    }

    public void activate(String tenant, String subject, String runId, String digest) {
        store.tx(c -> {
            JSONObject pin = pinned(c, tenant, subject, runId);
            requireRelease(pin, digest);
            if (!RuntimeSupport.strings(pin.getJSONArray("permissions")).contains("skill.activate"))
                throw new StoreException("RESOURCE_FORBIDDEN");
            if (first(rows(c, "SELECT digest FROM harness_run_skill WHERE run_id=? AND digest=?", runId, digest)) == null) {
                update(c, "INSERT INTO harness_run_skill VALUES (?,?)", runId, digest);
                store.emit(c, store.run(c, tenant, subject, runId, true), "skill.activated", new JSONObject().put("digest", digest), "coordinator");
            }
            return null;
        });
    }

    public static JSONObject requireRelease(JSONObject pin, String digest) {
        JSONArray releases = pin.getJSONArray("releases");
        for (int i = 0; i < releases.length(); i++)
            if (releases.getJSONObject(i).getString("digest").equals(digest)) return releases.getJSONObject(i);
        throw new StoreException("RESOURCE_FORBIDDEN");
    }

    public JSONObject workspace(JdbcRunStore.Lease lease, long duration) {
        if (duration < 100 || duration > 60000) throw new StoreException("INVALID_LEASE_DURATION");
        return store.tx(c -> {
            JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, r, lease);
            JSONObject pin = pinned(c, lease.tenant, lease.subject, lease.runId), runtime = pin.getJSONObject("runtime");
            long until = Math.min(store.now(c) + duration, r.getLong("lease_until"));
            JSONObject old = first(rows(c, "SELECT * FROM harness_workspace WHERE run_id=?" + lock(), lease.runId));
            String workspace = hash(new JSONArray().put(lease.tenant).put(lease.subject).put(lease.runId));
            if (old == null)
                update(c, "INSERT INTO harness_workspace VALUES (?,?,?,?,?,?,?,?,?,0)", lease.runId, workspace, lease.tenant, lease.subject, lease.owner, lease.fence, until, runtime.getLong("maxWorkspaceBytes"), runtime.getInt("maxWorkspaceFiles"));
            else
                update(c, "UPDATE harness_workspace SET owner_id=?,fence=?,expires_at=?,released=0 WHERE run_id=?", lease.owner, lease.fence, until, lease.runId);
            return first(rows(c, "SELECT * FROM harness_workspace WHERE run_id=?", lease.runId));
        });
    }

    public <T> T workspaceAction(JdbcRunStore.Lease lease, WorkspaceAction<T> action) {
        return store.tx(c -> {
            JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, r, lease);
            JSONObject row = first(rows(c, "SELECT * FROM harness_workspace WHERE run_id=?" + lock(), lease.runId));
            if (row == null || row.getInt("released") != 0 || row.getLong("fence") != lease.fence || !row.getString("owner_id").equals(lease.owner) || row.getLong("expires_at") <= store.now(c))
                throw new StoreException("STALE_WORKSPACE_LEASE");
            return action.apply(row);
        });
    }

    public void releaseWorkspace(JdbcRunStore.Lease lease) {
        store.tx(c -> {
            JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, r, lease);
            if (!r.getString("execution_settlement").equals("confirmed"))
                throw new StoreException("WORKSPACE_EXECUTION_UNSETTLED");
            update(c, "UPDATE harness_workspace SET released=1 WHERE run_id=? AND fence=?", lease.runId, lease.fence);
            return null;
        });
    }

    public JSONObject saveContext(JdbcRunStore.Lease lease, long expected, JSONObject snapshot) {
        String digest = hash(snapshot);
        if (RuntimeSupport.bytes(snapshot).length > 2097152) throw new StoreException("CONTEXT_SNAPSHOT_TOO_LARGE");
        return store.tx(c -> {
            JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, r, lease);
            JSONObject pin = pinned(c, lease.tenant, lease.subject, lease.runId);
            if (!snapshot.getString("runId").equals(lease.runId) || !snapshot.getString("configDigest").equals(hash(pin)))
                throw new StoreException("CONTEXT_BINDING_MISMATCH");
            JSONObject old = first(rows(c, "SELECT digest,run_version FROM harness_context WHERE digest=? AND run_id=?", digest, lease.runId));
            if (old != null)
                return new JSONObject().put("digest", digest).put("runVersion", old.getLong("run_version"));
            store.version(r, expected);
            if (snapshot.getLong("basisVersion") != expected) throw new StoreException("CONTEXT_STALE_BASIS");
            update(c, "INSERT INTO harness_context VALUES (?,?,?,?,?,?)", digest, lease.runId, lease.tenant, lease.subject, expected + 1, canonical(snapshot));
            store.emit(c, r, "context.checkpoint", new JSONObject().put("digest", digest).put("policyVersion", snapshot.getString("policyVersion")), "coordinator");
            return new JSONObject().put("digest", digest).put("runVersion", expected + 1);
        });
    }

    /**
     * A single transaction reads authoritative input, intent order and execution evidence.
     */
    public JSONObject contextBasis(String tenant, String subject, String runId) {
        return store.tx(c -> {
            JSONObject r = store.run(c, tenant, subject, runId, true), pin = pinned(c, tenant, subject, runId);
            return new JSONObject().put("version", r.getLong("version")).put("pin", pin)
                    .put("events", rows(c, "SELECT event_seq,event_type,data_json FROM harness_event WHERE run_id=? ORDER BY event_seq", runId))
                    .put("rounds", rows(c, "SELECT * FROM harness_round WHERE run_id=? ORDER BY intent_id,attempt", runId))
                    .put("calls", rows(c, "SELECT * FROM harness_tool_call WHERE run_id=? ORDER BY intent_id,attempt", runId));
        });
    }

    public JSONObject context(String tenant, String subject, String runId, String digest) {
        return store.tx(c -> {
            pinned(c, tenant, subject, runId);
            JSONObject row = first(rows(c, "SELECT snapshot_json,digest FROM harness_context WHERE tenant=? AND subject_id=? AND run_id=? AND digest=?", tenant, subject, runId, digest));
            if (row == null) throw new StoreException("CONTEXT_NOT_FOUND");
            JSONObject value = new JSONObject(row.getString("snapshot_json"));
            if (!hash(value).equals(row.getString("digest"))) throw new StoreException("CONTEXT_CORRUPT");
            return value;
        });
    }

    public String latestContext(String tenant, String subject, String runId) {
        return store.tx(c -> {
            pinned(c, tenant, subject, runId);
            JSONObject row = first(rows(c, "SELECT digest FROM harness_context WHERE run_id=? ORDER BY run_version DESC LIMIT 1", runId));
            if (row == null) throw new StoreException("CONTEXT_NOT_FOUND");
            return row.getString("digest");
        });
    }
}
