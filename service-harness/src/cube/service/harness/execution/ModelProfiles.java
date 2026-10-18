package cube.service.harness.execution;

import cube.service.harness.runtime.*;
import org.json.*;
import java.nio.charset.StandardCharsets;

/** Versioned wire budget, not a claim to know a remote server's tokenizer or chat template. */
public final class ModelProfiles {
    public static String digest(String adapter) {return RuntimeSupport.sha(adapter.getBytes(StandardCharsets.UTF_8));}
    public static String adapterDigest(String adapter) {try{return new JSONObject(new String(cube.service.harness.protocol.Contract.resource((adapter.endsWith("-v2")?"inference":"execution")+"/adapter-profiles.json"),StandardCharsets.UTF_8)).getJSONObject(adapter).getString("digest");}catch(Exception e){throw new IllegalArgumentException("ADAPTER_PROFILE_MISSING");}}
    public static JSONObject tokenizer() {return new JSONObject().put("id","transport-bytes-v1").put("version","1").put("digest",digest("transport-bytes-v1:wire-limit-only;remote-tokenizer-unverified"));}
    public static JSONObject model(String name) {
        String adapter=name.equals("Baize")?"openai-json-v2":"openai-tools-v2";
        return new JSONObject().put("schemaVersion",3).put("kind","model").put("id",name).put("version","2")
            .put("backend","java-inference").put("adapter",adapter).put("adapterDigest",adapterDigest(adapter))
            .put("capabilities",new JSONArray().put("resource_decisions")).put("tokenizer",tokenizer()).put("template",ContextEngine.template())
            .put("templateOverhead",4096).put("maxContextTokens",65536).put("certification","offline-adapter")
            .put("nativeTools",name.equals("Baize2")).put("generation",new JSONObject().put("temperature",0).put("topP",1).put("stream",true).put("maxRepairs",1));
    }
}
