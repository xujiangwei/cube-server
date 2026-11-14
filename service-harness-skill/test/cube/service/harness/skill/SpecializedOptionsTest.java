package cube.service.harness.skill;

import cube.common.entity.SpecializedDetectionInput;
import cube.service.harness.execution.QueryProvider;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.util.Properties;

/** Live media admission and strict profile options use the same detection contract. */
public final class SpecializedOptionsTest {
    static int assertions;
    public static void main(String[] args) {
        Properties settings=new Properties();settings.setProperty("capability.allowed.units","SpecializedDetection");
        CapabilityInvokeProvider provider=new CapabilityInvokeProvider(settings);
        JSONObject option=new JSONObject().put("operation","detect").put("profile","thing")
                .put("classes",new JSONArray().put("person").put("car").put("person"));
        JSONObject config=config(option);
        AnalysisProfiles.validate(config);
        JSONObject forwarded=CapabilityInvokeProvider.unitOptions(config.getJSONArray("profiles").getJSONObject(0));
        check(forwarded.getJSONArray("classes").length()==3,"classes reach detection input");
        JSONObject wire=new SpecializedDetectionInput(new JSONObject().put("files",new JSONArray().put(repeat('a',64)))
                .put("option",forwarded)).toJSON();
        check(wire.getJSONObject("option").getJSONArray("classes").length()==2,"wire contract deduplicates classes");
        for(Object invalid:new Object[]{JSONObject.NULL,new JSONArray(),new JSONArray().put(1),new JSONArray().put(""),"person"}) {
            reject(()->AnalysisProfiles.validate(config(new JSONObject(option.toString()).put("classes",invalid))));
        }
        reject(()->AnalysisProfiles.validate(config(new JSONObject(option.toString()).put("unknown",true))));
        AnalysisProfiles.validate(config(new JSONObject().put("operation","detect").put("profile","future-profile").put("sliced",true)));
        JSONObject fire=descriptor("fire","fire-smoke"),thing=descriptor("thing","thing");
        JSONArray units=new JSONArray().put(fire).put(thing);
        QueryProvider.ProviderInvocationLookup lookup=new QueryProvider.ProviderInvocationLookup() {
            public JSONObject source(String ref) { throw new AssertionError("Unexpected source lookup"); }
            public JSONArray mediaUnits() { return units; }
        };
        provider.preflightConfiguration(lookup,"analysis-profiles.json",config);assertions++;
        thing.put("availableImages",0);provider.preflightConfiguration(lookup,"analysis-profiles.json",config);assertions++;
        thing.put("availableImages",1).put("ready",false);reject(()->provider.preflightConfiguration(lookup,"analysis-profiles.json",config));
        thing.put("ready",true).put("statusExpiresAt",0);reject(()->provider.preflightConfiguration(lookup,"analysis-profiles.json",config));
        thing.put("statusExpiresAt",System.currentTimeMillis()+30000);
        JSONObject sliced=config(new JSONObject(option.toString()).put("sliced",true));
        reject(()->provider.preflightConfiguration(lookup,"analysis-profiles.json",sliced));
        thing.put("slicedProfiles",new JSONArray().put("thing"));
        provider.preflightConfiguration(lookup,"analysis-profiles.json",sliced);assertions++;
        System.out.println("SpecializedOptionsTest: "+assertions+" assertions passed");
    }
    static JSONObject descriptor(String id,String profile) {
        return new JSONObject().put("unit","SpecializedDetection").put("instanceId",id).put("capacity",1)
                .put("availableImages",1).put("ready",true).put("statusExpiresAt",System.currentTimeMillis()+30000)
                .put("profiles",new JSONArray().put(profile)).put("slicedProfiles",new JSONArray());
    }
    static JSONObject config(JSONObject option) {
        return new JSONObject().put("version","1").put("profiles",new JSONArray().put(new JSONObject()
                .put("id","objects").put("unit","SpecializedDetection").put("adapter","specialized-detection")
                .put("mediaTypes",new JSONArray().put("image/jpeg")).put("optionsJson",option.toString())));
    }
    static String repeat(char c,int n) { return new String(new char[n]).replace('\0',c); }
    static void reject(Runnable action) {
        try { action.run();throw new AssertionError("Invalid/unavailable options accepted"); }
        catch(StoreException | IllegalArgumentException expected) { assertions++; }
    }
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message);assertions++; }
}
