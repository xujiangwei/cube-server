package cube.service.harness.store;

import static cube.service.harness.store.JdbcRunStore.*;

import cube.service.harness.files.FileConfig;
import java.sql.Connection;
import java.util.*;
import org.json.*;

/** Persistent file identities, admission pins and fenced delete claims. No filesystem I/O here. */
public final class FileStore {
  private final JdbcRunStore store;
  private final String lock;
  public final FileConfig config;
  public final String node = "file-" + UUID.randomUUID();

  public FileStore(JdbcRunStore store, boolean mysql, FileConfig config) {
    this.store = store;
    this.config = config;
    lock = mysql ? " FOR UPDATE" : "";
  }

  public long now() {
    return store.tx(store::now);
  }

  public void bindRoot(String instance) {
    store.tx(
        c -> {
          rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1" + lock);
          JSONObject prior =
              first(
                  rows(
                      c,
                      "SELECT instance_id FROM harness_file_root WHERE root_id=?",
                      config.rootId));
          if (prior == null)
            update(c, "INSERT INTO harness_file_root VALUES (?,?)", config.rootId, id(instance));
          else if (!instance.equals(prior.getString("instance_id")))
            throw new StoreException("FILE_ROOT_IDENTITY_MISMATCH");
          return null;
        });
  }

  public void policy() {
    store.tx(
        c -> {
          long time = store.now(c);
          // The store identity row serializes registration even when the policy row is new.
          rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1" + lock);
          if (first(
                  rows(
                      c,
                      "SELECT node_id FROM harness_file_policy WHERE root_id=? AND policy_hash<>? AND lease_until>?",
                      config.rootId,
                      config.policy,
                      time))
              != null) throw new StoreException("FILE_POLICY_CONFLICT");
          if (update(
                  c,
                  "UPDATE harness_file_policy SET policy_hash=?,lease_until=? WHERE node_id=?",
                  config.policy,
                  time + Math.max(120000, 3 * config.scan),
                  node)
              == 0)
            update(
                c,
                "INSERT INTO harness_file_policy VALUES (?,?,?,?)",
                node,
                config.rootId,
                config.policy,
                time + Math.max(120000, 3 * config.scan));
          return null;
        });
  }

  public void close() {
    store.tx(
        c -> {
          update(c, "UPDATE harness_file_policy SET lease_until=0 WHERE node_id=?", node);
          return null;
        });
  }

  private JSONObject file(Connection c, String tenant, String subject, String id, boolean locking)
      throws Exception {
    JSONObject r =
        first(
            rows(
                c,
                "SELECT * FROM harness_file WHERE file_id=? AND tenant=? AND subject_id=? AND root_id=?"
                    + (locking ? lock : ""),
                id(id),
                tenant,
                subject,
                config.rootId));
    if (r == null) throw new StoreException("FILE_NOT_FOUND");
    return r;
  }

  public JSONObject get(String tenant, String subject, String id) {
    return store.tx(c -> file(c, tenant, subject, id, false));
  }

  private void ready(Connection c, JSONObject r) throws Exception {
    if (!r.isNull("run_id")
        && first(
                rows(
                    c,
                    "SELECT run_id FROM harness_tool_revocation WHERE run_id=?",
                    r.getString("run_id")))
            != null) throw new StoreException("FILE_FORBIDDEN");
    if (r.getLong("expires_at") <= store.now(c)
        || Arrays.asList("expired", "delete_pending", "deleted").contains(r.getString("status")))
      throw new StoreException("FILE_EXPIRED");
    if (!r.getString("status").equals("ready")) throw new StoreException("FILE_NOT_READY");
  }

  public JSONObject create(
      String tenant, String subject, String key, String kind, String run, JSONObject request) {
    return create(tenant, subject, key, kind, run, request, null);
  }

  public JSONObject create(Lease lease, String key, JSONObject request) {
    return create(lease.tenant, lease.subject, key, "artifact", lease.runId, request, lease);
  }

  private JSONObject create(
      String tenant,
      String subject,
      String key,
      String kind,
      String run,
      JSONObject request,
      Lease lease) {
    String
        req =
            hash(
                new JSONArray()
                    .put(tenant)
                    .put(subject)
                    .put(kind)
                    .put(run == null ? JSONObject.NULL : run)
                    .put(id(key))),
        digest = hash(request);
    return store.tx(
        c -> {
          if (lease != null) {
            store.owner(c, store.run(c, tenant, subject, run, true), lease);
            if (first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", run))
                != null) throw new StoreException("FILE_FORBIDDEN");
          }
          rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1" + lock);
          JSONObject prior =
              first(rows(c, "SELECT * FROM harness_file_request WHERE request_id=?", req));
          if (prior != null) {
            if (!digest.equals(prior.getString("payload_hash")))
              throw new StoreException("IDEMPOTENCY_CONFLICT");
            return file(c, tenant, subject, prior.getString("file_id"), false);
          }
          if (!Arrays.asList("input", "artifact", "temp").contains(kind))
            throw new StoreException("INVALID_FILE_KIND");
          if (run != null) {
            JSONObject r = store.run(c, tenant, subject, run, true);
            if (!r.getString("state").equals("running"))
              throw new StoreException("RUN_NOT_RUNNING");
          }
          long size = request.getLong("size");
          if (size < 0 || size > config.maxFile) throw new StoreException("FILE_TOO_LARGE");
          long reserved =
              first(
                      rows(
                          c,
                          "SELECT COALESCE(SUM(size_bytes),0) AS n FROM harness_file WHERE root_id=? AND status<>'deleted'",
                          config.rootId))
                  .getLong("n");
          if (size > config.maxBytes - reserved) throw new StoreException("FILE_QUOTA_EXCEEDED");
          String name = request.getString("fileName"), media = request.getString("mediaType");
          if (!name.matches("[^\\\\/\\p{Cntrl}]{1,256}")
              || name.equals(".")
              || name.equals("..")
              || !media.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))
            throw new StoreException("INVALID_FILE_METADATA");
          String id = UUID.randomUUID().toString(),
              storage = hash(new JSONArray().put(id).put(UUID.randomUUID().toString()));
          long now = store.now(c);
          update(
              c,
              "INSERT INTO harness_file (file_id,tenant,subject_id,kind,run_id,storage_key,root_id,file_name,media_type,size_bytes,sha256,status,created_at,expires_at,policy_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,'uploading',?,?,?)",
              id,
              tenant,
              subject,
              kind,
              run,
              storage,
              config.rootId,
              name,
              media,
              size,
              request.getString("sha256"),
              now,
              now + config.upload,
              config.policy);
          update(c, "INSERT INTO harness_file_request VALUES (?,?,?)", req, digest, id);
          return file(c, tenant, subject, id, false);
        });
  }

  public JSONObject beginWrite(String t, String s, String id, String pin) {
    return store.tx(
        c -> {
          JSONObject r = file(c, t, s, id, true);
          if (r.getLong("expires_at") <= store.now(c)) throw new StoreException("FILE_EXPIRED");
          if (!r.getString("status").equals("uploading"))
            throw new StoreException("FILE_UPLOAD_CLOSED");
          if (first(rows(c, "SELECT pin_id FROM harness_file_pin WHERE file_id=?", id)) != null)
            throw new StoreException("FILE_BUSY");
          update(
              c,
              "INSERT INTO harness_file_pin VALUES (?,?,NULL,?,'upload',?)",
              pin,
              id,
              node,
              r.getLong("expires_at"));
          return r;
        });
  }

  public void uploaded(String t, String s, String id, String digest) {
    store.tx(
        c -> {
          JSONObject r = file(c, t, s, id, true);
          if (!r.getString("status").equals("uploading") || r.getLong("expires_at") <= store.now(c))
            throw new StoreException("FILE_EXPIRED");
          if (!digest.equals(r.getString("sha256")))
            throw new StoreException("FILE_DIGEST_MISMATCH");
          update(c, "UPDATE harness_file SET upload_hash=? WHERE file_id=?", digest, id);
          return null;
        });
  }

  public JSONObject complete(String t, String s, String id, String digest) {
    return store.tx(
        c -> {
          JSONObject r = file(c, t, s, id, true);
          if (!digest.equals(r.getString("sha256")))
            throw new StoreException("FILE_DIGEST_MISMATCH");
          if (r.getString("status").equals("ready")) {
            ready(c, r);
            return r;
          }
          if (r.getLong("expires_at") <= store.now(c) || !r.getString("status").equals("uploading"))
            throw new StoreException("FILE_EXPIRED");
          if (!digest.equals(r.optString("upload_hash"))
              || first(rows(c, "SELECT pin_id FROM harness_file_pin WHERE file_id=?", id)) != null)
            throw new StoreException("FILE_UPLOAD_INCOMPLETE");
          long time = store.now(c),
              ttl =
                  r.getString("kind").equals("input")
                      ? config.input
                      : r.getString("kind").equals("temp") ? config.grace : config.artifact;
          update(
              c,
              "UPDATE harness_file SET status='ready',ready_at=?,expires_at=?,policy_hash=? WHERE file_id=?",
              time,
              time + ttl,
              config.policy,
              id);
          return file(c, t, s, id, false);
        });
  }

  public void unpin(String pin) {
    store.tx(
        c -> {
          update(c, "DELETE FROM harness_file_pin WHERE pin_id=? AND owner_id=?", pin, node);
          return null;
        });
  }

  public JSONObject pin(String t, String s, String id, String pin, String run, String kind) {
    return store.tx(
        c -> {
          JSONObject r = file(c, t, s, id, true);
          JSONObject old = first(rows(c, "SELECT * FROM harness_file_pin WHERE pin_id=?", pin));
          if (old != null) {
            if (!kind.equals(old.getString("kind"))
                || !Objects.equals(
                    run, old.opt("run_id") == JSONObject.NULL ? null : old.optString("run_id"))
                || !id.equals(old.getString("file_id"))
                || (!node.equals(old.getString("owner_id")) && !kind.equals("consume")))
              throw new StoreException("FILE_PIN_CONFLICT");
            if (kind.equals("consume")) {
              if (first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", run))
                  != null) throw new StoreException("FILE_FORBIDDEN");
              update(c, "UPDATE harness_file_pin SET owner_id=? WHERE pin_id=?", node, pin);
            }
            return r;
          }
          ready(c, r);
          if (run != null) {
            JSONObject rr = store.run(c, t, s, run, true);
            if (!rr.getString("state").equals("running"))
              throw new StoreException("RUN_NOT_RUNNING");
            if (first(
                    rows(
                        c,
                        "SELECT run_id FROM harness_file_binding WHERE run_id=? AND file_id=?",
                        run,
                        id))
                == null) throw new StoreException("FILE_NOT_BOUND");
          }
          update(
              c,
              "INSERT INTO harness_file_pin VALUES (?,?,?,?,?,?)",
              pin,
              id,
              run,
              node,
              kind,
              kind.equals("download") ? store.now(c) + config.download : Long.MAX_VALUE);
          return r;
        });
  }

  public void validateBindings(Connection c, String t, String s, JSONArray ids) throws Exception {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < ids.length(); i++) {
      String id = ids.getString(i);
      if (!seen.add(id)) throw new StoreException("DUPLICATE_INPUT");
      JSONObject r = file(c, t, s, id, true);
      ready(c, r);
      if (!r.getString("kind").equals("input")) throw new StoreException("INPUT_REQUIRED");
    }
  }

  public void bind(Connection c, String t, String s, String run, JSONArray ids) throws Exception {
    validateBindings(c, t, s, ids);
    for (int i = 0; i < ids.length(); i++) {
      String id = ids.getString(i);
      if (first(
              rows(
                  c,
                  "SELECT run_id FROM harness_file_binding WHERE run_id=? AND file_id=?",
                  run,
                  id))
          == null) update(c, "INSERT INTO harness_file_binding VALUES (?,?)", run, id);
    }
  }

  public JSONArray list(String t, String s, String run) {
    return store.tx(
        c -> {
          store.run(c, t, s, run, false);
          return rows(
              c,
              "SELECT * FROM harness_file WHERE tenant=? AND subject_id=? AND run_id=? AND kind='artifact' ORDER BY created_at,file_id",
              t,
              s,
              run);
        });
  }

  public void checkDelivery(String t, String s, String run) {
    store.tx(
        c -> {
          JSONArray all =
              rows(
                  c,
                  "SELECT * FROM harness_file WHERE tenant=? AND subject_id=? AND run_id=? AND kind='artifact'",
                  t,
                  s,
                  run);
          for (int i = 0; i < all.length(); i++) ready(c, all.getJSONObject(i));
          return null;
        });
  }

  public JSONArray candidates() {
    return store.tx(
        c -> {
          long now = store.now(c);
          JSONArray batch =
              rows(
                  c,
                  "SELECT * FROM harness_file WHERE root_id=? AND ((status IN ('uploading','ready') AND expires_at<=?) OR status IN ('expired','delete_pending')) AND next_attempt<=? ORDER BY expires_at,file_id LIMIT "
                      + config.batch,
                  config.rootId,
                  now,
                  now);
          for (int i = 0; i < batch.length(); i++) {
            JSONObject r = batch.getJSONObject(i);
            if (Arrays.asList("ready", "uploading").contains(r.getString("status")))
              update(
                  c,
                  "UPDATE harness_file SET status='expired' WHERE file_id=? AND status IN ('ready','uploading') AND expires_at<=?",
                  r.getString("file_id"),
                  now);
          }
          return batch;
        });
  }

  public JSONObject claim(String id) {
    return store.tx(
        c -> {
          JSONObject r =
              first(
                  rows(
                      c,
                      "SELECT * FROM harness_file WHERE file_id=? AND root_id=?" + lock,
                      id,
                      config.rootId));
          long now = store.now(c);
          if (r == null
              || !Arrays.asList("expired", "delete_pending").contains(r.getString("status"))
              || r.getLong("claim_until") > now
              || r.getLong("next_attempt") > now) return null;
          if (first(rows(c, "SELECT pin_id FROM harness_file_pin WHERE file_id=?", id)) != null)
            return null;
          String run = r.optString("run_id");
          if (!run.isEmpty()) {
            JSONObject rr =
                first(
                    rows(
                        c,
                        "SELECT state,execution_settlement FROM harness_run WHERE run_id=?",
                        run));
            if (rr != null
                && (!Arrays.asList("succeeded", "failed", "cancelled", "timed_out")
                        .contains(rr.getString("state"))
                    || !rr.getString("execution_settlement").equals("confirmed"))) return null;
          }
          long fence = r.getLong("claim_fence") + 1;
          update(
              c,
              "UPDATE harness_file SET status='delete_pending',owner_id=?,claim_fence=?,claim_until=? WHERE file_id=?",
              node,
              fence,
              now + config.claim,
              id);
          return r.put("status", "delete_pending")
              .put("owner_id", node)
              .put("claim_fence", fence)
              .put("claim_until", now + config.claim);
        });
  }

  public void verifyClaim(JSONObject claim) {
    store.tx(
        c -> {
          JSONObject r =
              first(
                  rows(
                      c,
                      "SELECT * FROM harness_file WHERE file_id=?" + lock,
                      claim.getString("file_id")));
          if (r == null
              || !node.equals(r.optString("owner_id"))
              || r.getLong("claim_fence") != claim.getLong("claim_fence")
              || r.getLong("claim_until") <= store.now(c)
              || !r.getString("status").equals("delete_pending"))
            throw new StoreException("STALE_FILE_CLAIM");
          return null;
        });
  }

  public void deleted(JSONObject claim) {
    store.tx(
        c -> {
          if (update(
                  c,
                  "UPDATE harness_file SET status='deleted',deleted_at=?,claim_until=0,last_error=NULL WHERE file_id=? AND owner_id=? AND claim_fence=? AND status='delete_pending' AND claim_until>?",
                  store.now(c),
                  claim.getString("file_id"),
                  node,
                  claim.getLong("claim_fence"),
                  store.now(c))
              != 1) throw new StoreException("STALE_FILE_CLAIM");
          return null;
        });
  }

  public void failed(JSONObject claim, String code) {
    store.tx(
        c -> {
          int count = Math.min(30, claim.getInt("attempts"));
          long delay = Math.min(config.retryMax, config.retry * (1L << Math.min(20, count)));
          update(
              c,
              "UPDATE harness_file SET claim_until=0,next_attempt=?,attempts=attempts+1,last_error=? WHERE file_id=? AND owner_id=? AND claim_fence=? AND status='delete_pending'",
              store.now(c) + delay,
              code,
              claim.getString("file_id"),
              node,
              claim.getLong("claim_fence"));
          return null;
        });
  }
  /**
   * Only called while holding the exclusive OS root lock, proving no old local reader/writer
   * survives.
   */
  public void recoverPins() {
    store.tx(
        c -> {
          update(
              c,
              "DELETE FROM harness_file_pin WHERE owner_id<>? AND kind IN ('download','upload') AND file_id IN (SELECT file_id FROM harness_file WHERE root_id=?) AND owner_id NOT IN (SELECT node_id FROM harness_file_policy WHERE lease_until>?)",
              node,
              config.rootId,
              store.now(c));
          JSONArray pins =
              rows(
                  c,
                  "SELECT * FROM harness_file_pin WHERE kind='consume' AND file_id IN (SELECT file_id FROM harness_file WHERE root_id=?)",
                  config.rootId);
          for (int i = 0; i < pins.length(); i++) {
            JSONObject pin = pins.getJSONObject(i);
            JSONArray calls =
                rows(
                    c,
                    "SELECT * FROM harness_tool_call WHERE run_id=? AND status='confirmed'",
                    pin.getString("run_id"));
            for (int j = 0; j < calls.length(); j++) {
              JSONObject call = calls.getJSONObject(j),
                  payload = new JSONObject(call.getString("payload_json"));
              if (payload.getString("tool").equals("input.read_text")
                  && pin.getString("pin_id")
                      .equals(
                          "read-"
                              + hash(
                                  new JSONArray()
                                      .put(pin.getString("run_id"))
                                      .put(call.getString("intent_id"))
                                      .put(pin.getString("file_id")))))
                update(c, "DELETE FROM harness_file_pin WHERE pin_id=?", pin.getString("pin_id"));
            }
          }
          return null;
        });
  }

  public void owner(Lease l) {
    store.tx(
        c -> {
          store.owner(c, store.run(c, l.tenant, l.subject, l.runId, true), l);
          return null;
        });
  }

  public JSONObject metrics() {
    return store.tx(
        c ->
            new JSONObject()
                .put(
                    "states",
                    rows(
                        c,
                        "SELECT status,COUNT(*) AS count,COALESCE(SUM(size_bytes),0) AS bytes FROM harness_file WHERE root_id=? GROUP BY status",
                        config.rootId))
                .put(
                    "pins",
                    rows(
                        c,
                        "SELECT kind,COUNT(*) AS count FROM harness_file_pin WHERE file_id IN (SELECT file_id FROM harness_file WHERE root_id=?) GROUP BY kind",
                        config.rootId))
                .put(
                    "failures",
                    rows(
                        c,
                        "SELECT file_id,attempts,last_error,next_attempt FROM harness_file WHERE root_id=? AND last_error IS NOT NULL LIMIT 100",
                        config.rootId)));
  }
}
