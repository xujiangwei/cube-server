package cube.service.test;

import cube.aigc.SpecializedDetectionCapabilities;
import cube.common.entity.*;
import cube.service.aigc.unit.SpecializedDetectionScheduler;
import cube.service.aigc.unit.UnitScheduler;
import cube.service.aigc.HarnessMediaBridge;
import org.json.*;
import java.lang.reflect.Field;
import java.util.*;
import static cube.service.test.SpecializedDetectionTest.*;

/** Registration identity, lease evidence and heterogeneous provider admission. JDK 8. */
public final class SpecializedDetectionCapabilitiesTest {
    public static void main(String[] args) throws Exception {
        registration(); classes(); routing(); harnessInput(); pluginReadiness();
        System.out.println("SpecializedDetectionCapabilitiesTest: " + assertions + " assertions passed");
    }

    static void registration() {
        UnitScheduler registry = new UnitScheduler();
        Contact contact = new Contact(900L,"domain");
        Context context = new Context();
        AICapability old = new AICapability("SpecializedDetection","ComputerVision","2.0",
                Collections.singletonList("ObjectDetection"),"unchanged");
        AIGCUnit original = registry.setup(contact,Collections.singletonList(old),context).get(0);
        original.setRunning(true);
        String key = original.getQueryKey();
        for (int mask = 1; mask < 8; mask++) {
            List<String> names = new ArrayList<>(Collections.singletonList("ObjectDetection"));
            String[] profiles = {"uav-fire-smoke","fire-smoke","thing"};
            for(int i=0;i<3;i++) if((mask & (1<<i))!=0) names.add(profiles[i]);
            AICapability reported = new AICapability("SpecializedDetection","ComputerVision","2.0",names,"unchanged");
            AICapability decoded = new AICapability(reported.toJSON());
            AIGCUnit updated = registry.setup(contact,Collections.singletonList(decoded),context).get(0);
            check(updated == original && key.equals(updated.getQueryKey()),"subtasks preserve provider identity");
            check(updated.toJSON().getInt("runningCounter")==1,"registration preserves running count");
            check(updated.getCapability().getSubtasks().equals(names),"complete list refresh removes disabled profiles");
            check(SpecializedDetectionCapabilities.profiles(decoded).size()==Integer.bitCount(mask),"profile subset");
            decoded.getSubtasks().clear();
            check(decoded.getSubtasks().equals(names),"defensive subtask copy");
            check(!decoded.toJSON().has("specializedDetection"),"no capability extension field");
        }
        AICapability audio = new AICapability("Audio","AudioProcessing","2.0",Arrays.asList("A","B"),"text");
        check(SpecializedDetectionCapabilities.profiles(audio).isEmpty() && audio.containsSubtask("B"),"other units retain task semantics");
    }

    static void classes() {
        JSONObject input = image("thing");
        input.getJSONObject("option").put("classes",new JSONArray().put("person").put("car").put("person"));
        JSONObject parsed = new SpecializedDetectionInput(input).toJSON().getJSONObject("option");
        check(parsed.getJSONArray("classes").length()==2 && parsed.getJSONArray("classes").getString(1).equals("car"),"class order and deduplication");
        for(Object value:Arrays.asList(JSONObject.NULL,new JSONArray(),new JSONArray().put(1),new JSONArray().put(" "),"person")) {
            JSONObject invalid=image("thing");invalid.getJSONObject("option").put("classes",value);
            expect(1,()->new SpecializedDetectionInput(invalid));
        }
        JSONObject future=image("future");future.getJSONObject("option").put("sliced",true);
        check(new SpecializedDetectionInput(future).isSliced(),"shape validation does not hardcode profiles");
    }

    static JSONObject state(long sequence,String value,int maximum,String... sliced) {
        return new JSONObject().put("version",1).put("sequence",sequence).put("state",value)
                .put("maxSessions",maximum).put("slicedProfiles",new JSONArray(Arrays.asList(sliced)));
    }

    static void harnessInput() throws Exception {
        java.lang.reflect.Method method=HarnessMediaBridge.class.getDeclaredMethod("mediaInput",JSONObject.class,String.class);
        method.setAccessible(true);
        JSONObject execution=new JSONObject().put("unit","SpecializedDetection").put("adapter","specialized-detection")
                .put("content","text supplied by the Harness tool").put("option",image("thing").getJSONObject("option"));
        execution.getJSONObject("option").put("classes",new JSONArray().put("person"));
        JSONObject input=(JSONObject)method.invoke(null,execution,FILE);
        check(!input.has("content"),"Harness detection input excludes text content");
        check(new SpecializedDetectionInput(input).toUnitJSON(label("domain",1L)).getJSONObject("option")
                .getJSONArray("classes").getString(0).equals("person"),"Harness envelope reaches strict detection input");
        execution.put("unit","OmniMultimodal").put("adapter","multimodal");
        check(((JSONObject)method.invoke(null,execution,FILE)).getString("content").equals(execution.getString("content")),"multimodal text input retained");
    }

    static void pluginReadiness() throws Exception {
        try(Fixture f=new Fixture(1)) {
            AIGCUnit detection=f.service.units.get(0);
            AICapability capability=new AICapability("OmniForest","ComputerVision","1.0",
                    Collections.singletonList("ObjectDetection"),"OmniForest");
            f.service.units.add(new AIGCUnit(detection.getContact(),capability,detection.getContext()));
            check(f.service.getPluginUnits().report(detection.getContext(),PluginUnitInvokerTest.status(1,"ready",1,0)),"plugin readiness accepted");
            check(f.service.getSpecializedDetections().report(detection.getContext(),state(2,"initializing",2)),"independent detection initializing");
            check(f.service.getPluginUnits().status("OmniForest").getBoolean("ready"),"detection update preserves other plugin readiness");
            check(f.service.getSpecializedDetections().report(detection.getContext(),state(3,"failed",2)),"independent detection failure");
            check(f.service.getPluginUnits().status("OmniForest").getBoolean("ready"),"detection failure preserves other plugin readiness");
        }
    }

    static void routing() throws Exception {
        try(Fixture f=new Fixture(2)) {
            AIGCUnit fire=f.service.units.get(0),thing=f.service.units.get(1);
            fire.getCapability().setSubtasks(Arrays.asList("ObjectDetection","fire-smoke"));
            thing.getCapability().setSubtasks(Arrays.asList("ObjectDetection","thing"));
            SpecializedDetectionScheduler scheduler=f.service.getSpecializedDetections();
            check(scheduler.snapshot().getJSONArray("providers").getJSONObject(0).getInt("availableImages")==0,"changed configuration needs readiness");
            check(scheduler.report(fire.getContext(),state(2,"ready",3)),"fire status");
            check(scheduler.report(thing.getContext(),state(2,"ready",1)),"thing status");
            f.invoke(image("thing")).success();
            check(f.cellet.contexts.get(0)==thing.getContext(),"thing avoids fire-only node");
            f.invoke(image("fire-smoke")).success();
            check(f.cellet.contexts.get(1)==fire.getContext(),"fire avoids thing-only node");
            expect(1,()->f.invoke(image("unknown")));
            JSONObject sliced=image("thing");sliced.getJSONObject("option").put("sliced",true);
            expect(1,()->f.invoke(sliced));
            fire.getCapability().setSubtasks(Arrays.asList("ObjectDetection","fire-smoke","thing"));
            check(scheduler.report(fire.getContext(),state(3,"initializing",3,"thing")),"sliced provider initializing");
            expect(30,()->f.invoke(sliced));
            fire.getCapability().setSubtasks(Arrays.asList("ObjectDetection","fire-smoke"));
            check(scheduler.report(fire.getContext(),state(4,"ready",3)),"fire-only provider restored");
            check(!scheduler.report(new Context(),state(3,"ready",2)),"foreign context rejected");
            check(!scheduler.report(thing.getContext(),state(2,"failed",1)),"duplicate sequence rejected");
            check(!scheduler.report(thing.getContext(),state(3,"ready",1,"fire-smoke")),"slicing must be registered");
            check(!scheduler.report(thing.getContext(),state(3,"ready",0)),"invalid capacity rejected");
            check(!scheduler.report(thing.getContext(),state(3,"ready",1).put("sequence",3.5)),"sequence must be integral");
            check(scheduler.report(thing.getContext(),state(3,"failed",1)),"failure report");
            expect(30,()->f.invoke(image("thing")));
            check(scheduler.report(thing.getContext(),state(4,"ready",1)),"recovered node");
            Map<?,?> providers=(Map<?,?>)field(scheduler,"providers");
            Object p=providers.get(thing.getQueryKey());
            Field seen=p.getClass().getDeclaredField("reportedAt");seen.setAccessible(true);seen.setLong(p,System.currentTimeMillis()-30001);
            expect(30,()->f.invoke(image("thing")));
            check(!scheduler.descriptor(thing).getBoolean("ready"),"expired lease unavailable");
            check(scheduler.report(thing.getContext(),state(5,"ready",1)),"heartbeat restores lease");
            Properties policy=new Properties();policy.setProperty("specialized.detection.max.sessions","2");scheduler.configure(policy);
            check(scheduler.descriptor(fire).getInt("maxSessions")==2 && scheduler.descriptor(thing).getInt("maxSessions")==1,"Java only tightens Python limit");
            String nodeKey="specialized.detection.node."+fire.getContact().getDomain().getName()+"."+fire.getContact().getId()+".max.sessions";
            policy.setProperty(nodeKey,"4");scheduler.configure(policy);
            check(scheduler.descriptor(fire).getInt("maxSessions")==2,"node override cannot loosen global limit");
            policy.setProperty(nodeKey,"1");scheduler.configure(policy);
            check(scheduler.descriptor(fire).getInt("maxSessions")==1,"node policy may further tighten global limit");
            scheduler.configure(new Properties());
            check(scheduler.descriptor(fire).getInt("maxSessions")==3,"unconfigured Java uses Python capacity");
            SpecializedDetectionScheduler.Reservation reservation=scheduler.reserve(new SpecializedDetectionInput(image("thing")),f.service.token);
            expect(7,()->f.invoke(image("thing")));
            scheduler.release(reservation);
            JSONObject start=start();start.getJSONObject("option").put("profile","thing");
            Reply stream=f.invoke(start);stream.success();
            scheduler.onSetup(Collections.singletonList(thing));
            f.invoke(control("status",stream.id())).success();
            check(!scheduler.descriptor(thing).getBoolean("ready"),"registration preserves sessions while readiness is re-confirmed");
            check(scheduler.report(thing.getContext(),state(6,"stopping",1)),"stopping status");
            f.invoke(control("status",stream.id())).success();
            expect(30,()->f.invoke(image("thing")));
            f.cellet.status="stopped";f.invoke(control("stop",stream.id())).success();
            scheduler.onSetup(Collections.singletonList(thing));
            check(!scheduler.descriptor(thing).getBoolean("ready"),"registration refresh requires fresh evidence");
        }
    }
}
