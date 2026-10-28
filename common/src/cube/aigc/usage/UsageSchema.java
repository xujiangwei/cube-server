package cube.aigc.usage;

import java.util.*;

/** Identical accounting schema in module-owned databases; Console is a reader only. */
public final class UsageSchema {
    private UsageSchema() { }
    public static String prefix(String p) {
        if (!"aigc_ai_usage_".equals(p) && !"harness_ai_usage_".equals(p)) throw new IllegalArgumentException("INVALID_USAGE_PREFIX");
        return p;
    }
    public static List<String> statements(String prefix, boolean mysql) {
        String p = prefix(prefix), tail = mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin" : "";
        List<String> s = new ArrayList<>();
        s.add("CREATE TABLE " + p + "store (singleton INTEGER NOT NULL,store_id VARCHAR(64) NOT NULL,schema_version INTEGER NOT NULL,collected_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(singleton))" + tail);
        s.add("CREATE TABLE " + p + "call (meter_id VARCHAR(128) NOT NULL,domain VARCHAR(128) NOT NULL,contact_id BIGINT NOT NULL,module VARCHAR(64) NOT NULL,model VARCHAR(128) NOT NULL,unit_id VARCHAR(128) NOT NULL,run_id VARCHAR(128) NOT NULL,request_id VARCHAR(128) NOT NULL,started_at BIGINT NOT NULL,finished_at BIGINT NULL,status VARCHAR(32) NOT NULL,quality VARCHAR(16) NOT NULL,input_tokens BIGINT NULL,output_tokens BIGINT NULL,revision BIGINT NOT NULL,PRIMARY KEY(meter_id))" + tail);
        s.add("CREATE INDEX " + p + "contact_time ON " + p + "call (domain,contact_id,started_at)");
        s.add("CREATE INDEX " + p + "time ON " + p + "call (started_at,meter_id)");
        s.add("CREATE TABLE " + p + "revision (meter_id VARCHAR(128) NOT NULL,revision BIGINT NOT NULL,evidence_hash VARCHAR(64) NOT NULL,evidence_json TEXT NOT NULL,recorded_at BIGINT NOT NULL,PRIMARY KEY(meter_id,revision))" + tail);
        s.add("CREATE TABLE " + p + "hourly (bucket_id VARCHAR(64) NOT NULL,hour_at BIGINT NOT NULL,domain VARCHAR(128) NOT NULL,contact_id BIGINT NOT NULL,module VARCHAR(64) NOT NULL,model VARCHAR(128) NOT NULL,unit_id VARCHAR(128) NOT NULL,calls BIGINT NOT NULL,actual_calls BIGINT NOT NULL,estimated_calls BIGINT NOT NULL,unknown_calls BIGINT NOT NULL,pending_calls BIGINT NOT NULL,input_tokens BIGINT NOT NULL,output_tokens BIGINT NOT NULL,estimated_input BIGINT NOT NULL,estimated_output BIGINT NOT NULL,PRIMARY KEY(bucket_id))" + tail);
        s.add("CREATE INDEX " + p + "hour_contact ON " + p + "hourly (domain,contact_id,hour_at)");
        return s;
    }
}
