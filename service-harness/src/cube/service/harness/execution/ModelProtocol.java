package cube.service.harness.execution;
import cube.service.harness.protocol.*;
import cube.service.harness.runtime.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ModelProtocol {
    public static void verify() {
        try {
            JSONObject manifest=StrictJson.object(Contract.resource("execution/manifest.json"));
            for(String path:manifest.keySet())if(!RuntimeSupport.sha(Contract.resource("execution/"+path)).equals(manifest.getString(path)))throw new IllegalArgumentException("EXECUTION_CONTRACT_DIGEST_MISMATCH");
            JSONObject profiles=StrictJson.object(Contract.resource("execution/adapter-profiles.json"));
            for(String name:profiles.keySet()){JSONObject p=profiles.getJSONObject(name);if(!cube.service.harness.store.JdbcRunStore.hash(p.getJSONObject("spec")).equals(p.getString("digest")))throw new IllegalArgumentException("ADAPTER_PROFILE_DIGEST_MISMATCH");}
        }catch(java.io.IOException e){throw new IllegalArgumentException("EXECUTION_SCHEMA_MISSING");}
    }
    public static JSONObject parse(String schema,byte[] raw) {try{JSONObject v=StrictJson.object(raw);Contract.validate(v,StrictJson.object(Contract.resource("execution/schemas/"+schema+".json")));return v;}catch(java.io.IOException e){throw new IllegalArgumentException("EXECUTION_SCHEMA_MISSING");}}
    public static JSONObject outcome(String raw,boolean finished) {
        JSONObject o=StrictJson.object(raw.getBytes(StandardCharsets.UTF_8));
        parse("model-outcome",raw.getBytes(StandardCharsets.UTF_8));
        Set<String> allowed=new HashSet<>(Arrays.asList("status","decision","errorCode","usage","audit","cancellationObserved"));
        if(!allowed.containsAll(o.keySet())||!o.has("status")||!o.has("audit")||!o.has("cancellationObserved"))throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        String status=o.getString("status");if(!Arrays.asList("success","error","unknown").contains(status)||finished==status.equals("unknown"))throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        if(status.equals("success")){if(o.has("errorCode"))throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");Contract.parse("model-decision",RuntimeSupport.bytes(o.getJSONObject("decision")));}
        else if(o.has("decision")||!o.optString("errorCode").matches("[A-Z0-9_]{1,128}")||status.equals("unknown")&&o.has("usage"))throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        JSONArray audit=o.getJSONArray("audit");if(audit.length()>2)throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        if(o.has("usage")) {JSONObject u=o.getJSONObject("usage");if(!u.keySet().equals(new HashSet<>(Arrays.asList("input","output"))))throw new IllegalArgumentException("MODEL_USAGE_INVALID");for(String k:u.keySet()) {Object n=u.get(k);if(!(n instanceof Number)||new java.math.BigDecimal(n.toString()).scale()>0||u.getLong(k)<0||u.getLong(k)>1000000000000L)throw new IllegalArgumentException("MODEL_USAGE_INVALID");}}
        if(o.optBoolean("cancellationObserved",false)!=o.getBoolean("cancellationObserved"))throw new IllegalArgumentException("MODEL_OUTCOME_INVALID");
        return o;
    }
}
