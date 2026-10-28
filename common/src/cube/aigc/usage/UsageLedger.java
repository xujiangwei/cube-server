package cube.aigc.usage;

import cube.core.Storage;
import cube.storage.MySQLStorage;
import cube.storage.StorageType;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Durable per-attempt ledger. Every mutation and its hourly delta share one transaction. */
public final class UsageLedger {
    public interface Connections { Connection open() throws SQLException; }
    public interface Work<T> { T run(Connection c) throws Exception; }
    public final Connections connections;
    public final String prefix;
    public final boolean mysql;
    private static final String[] METRICS = {"calls","actual_calls","estimated_calls","unknown_calls","pending_calls","input_tokens","output_tokens","estimated_input","estimated_output"};

    public UsageLedger(Connections connections, String prefix, boolean mysql) {
        this.connections = connections; this.prefix = UsageSchema.prefix(prefix); this.mysql = mysql;
    }
    public static UsageLedger storage(Storage storage) {
        boolean mysql = storage.getType() == StorageType.MySQL;
        return new UsageLedger(mysql ? () -> ((MySQLStorage) storage).borrowConnection()
                : () -> DriverManager.getConnection("jdbc:sqlite:" + storage.getConfig().getString("file")), "aigc_ai_usage_", mysql);
    }
    public <T> T transaction(Work<T> work) {
        try (Connection c = connections.open()) {
            if (mysql) c.setAutoCommit(false);
            else try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); s.execute("PRAGMA synchronous=FULL"); s.execute("BEGIN IMMEDIATE"); }
            try {
                T value = work.run(c);
                if (mysql) c.commit(); else try (Statement s = c.createStatement()) { s.execute("COMMIT"); }
                return value;
            } catch (Exception e) {
                try { if (mysql) c.rollback(); else try (Statement s = c.createStatement()) { s.execute("ROLLBACK"); } } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (Exception e) { throw new IllegalStateException("AI_USAGE_STORAGE_UNAVAILABLE", e); }
    }
    /** Service startup only. Readers must never call this method. */
    public void initialize() {
        transaction(c -> {
            if (mysql) {
                JSONObject locked = one(c, "SELECT GET_LOCK(CONCAT(DATABASE(),?),10) AS acquired", ":" + prefix);
                if (locked == null || locked.optInt("acquired") != 1) throw new SQLException("USAGE_BOOTSTRAP_BUSY");
            }
            try {
                for (String sql : UsageSchema.statements(prefix, mysql)) {
                    if (sql.startsWith("CREATE TABLE")) sql = sql.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ");
                    try (Statement s = c.createStatement()) { s.execute(sql); }
                    catch (SQLException e) { if (!(sql.startsWith("CREATE INDEX") && (e.getErrorCode() == 1061 || e.getMessage().contains("already exists")))) throw e; }
                }
                JSONObject existing = one(c, "SELECT * FROM " + prefix + "store WHERE singleton=1");
                if (existing == null) {
                    long now = System.currentTimeMillis();
                    update(c, "INSERT INTO " + prefix + "store VALUES (1,?,1,?,?)", UUID.randomUUID().toString(), now, now);
                }
                metadata(c);
                // Publish the store marker before another initializer acquires the named lock.
                if (mysql) c.commit();
                return null;
            } finally { if (mysql) one(c, "SELECT RELEASE_LOCK(CONCAT(DATABASE(),?)) AS released", ":" + prefix); }
        });
    }
    public JSONObject metadata(Connection c) throws SQLException {
        JSONObject row = one(c, "SELECT * FROM " + prefix + "store WHERE singleton=1");
        if (row == null || row.getInt("schema_version") != 1) throw new SQLException("AI_USAGE_SCHEMA_UNSUPPORTED");
        return row;
    }
    private void lock(Connection c) throws SQLException {
        JSONObject m = one(c, "SELECT * FROM " + prefix + "store WHERE singleton=1" + (mysql ? " FOR UPDATE" : ""));
        if (m == null || m.getInt("schema_version") != 1) throw new SQLException("AI_USAGE_NOT_INITIALIZED");
    }
    public void begin(UsageContext identity, String id, String model, String unit, long now) {
        if (!identity.delegated) transaction(c -> { begin(c, identity, id, model, unit, now); return null; });
    }
    /** Also used inside the Harness dispatch claim transaction. */
    public void begin(Connection c, UsageContext identity, String id, String model, String unit, long now) throws SQLException {
        if (identity.delegated) return;
        lock(c);
        JSONObject old = one(c, "SELECT * FROM " + prefix + "call WHERE meter_id=?", valid(id,128));
        if (old != null) {
            if (!old.getString("domain").equals(identity.domain) || old.getLong("contact_id") != identity.contactId
                    || !old.getString("model").equals(model) || !old.getString("run_id").equals(identity.runId)
                    || !old.getString("module").equals(identity.module) || !old.getString("unit_id").equals(unit)
                    || !old.getString("request_id").equals(identity.requestId)) throw new SQLException("USAGE_ID_CONFLICT");
            return;
        }
        update(c, "INSERT INTO " + prefix + "call (meter_id,domain,contact_id,module,model,unit_id,run_id,request_id,started_at,status,quality,revision) VALUES (?,?,?,?,?,?,?,?,?,'running','pending',0)",
                id, identity.domain, identity.contactId, valid(identity.module,64), valid(model,128), valid(unit,128), valid(identity.runId,128), valid(identity.requestId,128), now);
        JSONObject row = one(c, "SELECT * FROM " + prefix + "call WHERE meter_id=?", id);
        aggregate(c, row, null);
        touch(c);
    }
    public static JSONObject evidence(String id, long revision, String status, String quality, Long input, Long output, long finished) {
        if (!("actual".equals(quality) || "estimated".equals(quality) || "unknown".equals(quality))) throw new IllegalArgumentException("INVALID_USAGE_QUALITY");
        if ((input == null) != (output == null) || (input != null && (input < 0 || output < 0))) throw new IllegalArgumentException("INVALID_USAGE_TOKENS");
        if (!"unknown".equals(quality) && input == null) throw new IllegalArgumentException("MISSING_USAGE_TOKENS");
        return new JSONObject().put("meterId", id).put("revision", revision).put("status", valid(status,32)).put("quality", quality)
                .put("input", input == null ? JSONObject.NULL : input).put("output", output == null ? JSONObject.NULL : output).put("finished", finished);
    }
    public void settle(JSONObject evidence) { transaction(c -> { settle(c, evidence); return null; }); }
    public void settle(Connection c, JSONObject e) throws SQLException {
        lock(c);
        // Validate replayed journal data as strictly as live evidence.
        evidence(e.getString("meterId"), e.getLong("revision"), e.getString("status"), e.getString("quality"),
                e.isNull("input") ? null : e.getLong("input"), e.isNull("output") ? null : e.getLong("output"), e.getLong("finished"));
        String id = e.getString("meterId"), hash = evidenceHash(e); long revision = e.getLong("revision");
        if (revision <= 0) throw new SQLException("INVALID_USAGE_REVISION");
        JSONObject known = one(c, "SELECT evidence_hash FROM " + prefix + "revision WHERE meter_id=? AND revision=?", id, revision);
        if (known != null) { if (!known.getString("evidence_hash").equals(hash)) throw new SQLException("USAGE_EVIDENCE_CONFLICT"); return; }
        JSONObject old = one(c, "SELECT * FROM " + prefix + "call WHERE meter_id=?", id);
        if (old == null) throw new SQLException("USAGE_CALL_NOT_FOUND");
        if (revision <= old.getLong("revision") || ("actual".equals(old.getString("quality")) && !"actual".equals(e.getString("quality")))) return;
        update(c, "INSERT INTO " + prefix + "revision VALUES (?,?,?,?,?)", id, revision, hash, e.toString(), System.currentTimeMillis());
        update(c, "UPDATE " + prefix + "call SET status=?,quality=?,input_tokens=?,output_tokens=?,finished_at=?,revision=? WHERE meter_id=?",
                e.getString("status"), e.getString("quality"), e.isNull("input") ? null : e.getLong("input"), e.isNull("output") ? null : e.getLong("output"), e.getLong("finished"), revision, id);
        aggregate(c, one(c,"SELECT * FROM " + prefix + "call WHERE meter_id=?",id), old);
        touch(c);
    }
    private void touch(Connection c) throws SQLException { update(c, "UPDATE " + prefix + "store SET updated_at=? WHERE singleton=1", System.currentTimeMillis()); }
    private static long[] values(JSONObject r) {
        long[] v = new long[9]; if (r == null || "not_dispatched".equals(r.optString("status"))) return v;
        v[0]=1; String q=r.getString("quality");
        int i="actual".equals(q)?1:"estimated".equals(q)?2:"unknown".equals(q)?3:4; v[i]=1;
        if (i==1 && "unknown".equals(r.getString("status"))) v[4]=1;
        if (i==1 || i==2) { v[i==1?5:7]=r.getLong("input_tokens"); v[i==1?6:8]=r.getLong("output_tokens"); } return v;
    }
    private void aggregate(Connection c, JSONObject r, JSONObject old) throws SQLException {
        long hour = r.getLong("started_at") / 3600000L * 3600000L;
        String key=hash(new JSONArray().put(hour).put(r.getString("domain")).put(r.getLong("contact_id")).put(r.getString("module")).put(r.getString("model")).put(r.getString("unit_id")).toString());
        if (one(c,"SELECT bucket_id FROM "+prefix+"hourly WHERE bucket_id=?",key)==null)
            update(c,"INSERT INTO "+prefix+"hourly VALUES (?,?,?,?,?,?,?,0,0,0,0,0,0,0,0,0)",key,hour,r.getString("domain"),r.getLong("contact_id"),r.getString("module"),r.getString("model"),r.getString("unit_id"));
        long[] before=values(old), after=values(r); StringJoiner set=new StringJoiner(","); Object[] args=new Object[10];
        for(int i=0;i<9;i++){set.add(METRICS[i]+"="+METRICS[i]+"+?");args[i]=Math.subtractExact(after[i],before[i]);}args[9]=key;
        update(c,"UPDATE "+prefix+"hourly SET "+set+" WHERE bucket_id=?",args);
    }
    public static String valid(String s,int max) { if(s==null || s.length()>max)throw new IllegalArgumentException("INVALID_USAGE_FIELD");return s; }
    public static String evidenceHash(JSONObject e) { return hash(new JSONArray().put(e.getString("meterId")).put(e.getLong("revision"))
            .put(e.getString("quality")).put(e.opt("input")).put(e.opt("output")).toString()); }
    public static String hash(String value) {
        try { StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))s.append(String.format("%02x",b&255));return s.toString(); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static int update(Connection c,String sql,Object... args)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,args);return s.executeUpdate();}}
    public static JSONObject one(Connection c,String sql,Object... args)throws SQLException{JSONArray rows=rows(c,sql,args);return rows.length()==0?null:rows.getJSONObject(0);}
    public static JSONArray rows(Connection c,String sql,Object... args)throws SQLException{
        JSONArray a=new JSONArray();try(PreparedStatement s=c.prepareStatement(sql)){bind(s,args);try(ResultSet r=s.executeQuery()){ResultSetMetaData m=r.getMetaData();while(r.next()){JSONObject o=new JSONObject();for(int i=1;i<=m.getColumnCount();i++){Object v=r.getObject(i);o.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),v==null?JSONObject.NULL:v);}a.put(o);}}}return a;
    }
    private static void bind(PreparedStatement s,Object[] args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
}
