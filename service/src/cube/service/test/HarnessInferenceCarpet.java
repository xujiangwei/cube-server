package cube.service.test;

import cell.api.Nucleus;
import cell.carpet.CellListener;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cube.core.Kernel;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.*;
import cube.aigc.ModelConfig;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only Cell host: actual AIGCService/Unit queue; synthetic hardware replies, no weights or network models. */
public final class HarnessInferenceCarpet implements CellListener {
    private Kernel kernel;
    private Fixture service;
    private final ScheduledExecutorService hardware=Executors.newScheduledThreadPool(2);
    final class Transport extends ChatCompletionServiceTest.FakeCellet {
        @Override public boolean speak(TalkContext context,Primitive primitive) {
            boolean sent=super.speak(context,primitive);
            cell.core.talk.dialect.ActionDialect dialect=new cell.core.talk.dialect.ActionDialect(primitive);
            if(dialect.getName().equals(AIGCAction.ChatCompletion.name)) {
                JSONObject data=new Packet(dialect).data;String id=data.getString("requestId");
                hardware.schedule(()->{
                    JSONObject request=data.getJSONObject("request"),result=ChatCompletionServiceTest.result();
                    result.getJSONObject("message").put("content","{\"kind\":\"final\",\"text\":\"fixture answer\"}");
                    ChatCompletionServiceTest.event(service,context,id,1,"started",new JSONObject());
                    ChatCompletionServiceTest.event(service,context,id,2,"completed",new JSONObject().put("result",result));
                },2200,TimeUnit.MILLISECONDS);
            }
            return sent;
        }
    }
    final class Fixture extends ChatCompletionServiceTest.FakeService {
        final List<AIGCUnit> units=new ArrayList<>();
        Fixture(Transport transport)throws Exception {
            super(transport);
            for(String name:Arrays.asList("Baize","Baize2")) {
                AICapability cap=new AICapability(name,"Text","1",Collections.singletonList("FeatureExtraction"),"offline");
                cap.setChatCompletion(new JSONObject().put("text",true).put("stream",true).put("tools",name.equals("Baize2"))
                    .put("parameters",new JSONArray().put("max_tokens").put("temperature").put("top_p").put("tool_choice")));
                AIGCUnit unit=new AIGCUnit(new Contact(100L+units.size(),"domain"),cap,new ChatCompletionServiceTest.Context());unit.setTimestamp(0);units.add(unit);
            }
        }
        @Override public List<AIGCUnit> getAllUnits(){return units;}
        @Override public List<ModelConfig> getModelConfigs(){return Arrays.asList(
            new ModelConfig("Baize","Baize","","http://unused/",new JSONObject().put("unit","Baize")),
            new ModelConfig("Baize2","Baize2","","http://unused/",new JSONObject().put("unit","Baize2")));}
        @Override public void start(){started.set(true);}
        @Override public void stop(){getCompletions().stop();queue.shutdown();started.set(false);}
    }
    public void cellPreinitialize(Nucleus nucleus) {
        try {kernel=new Kernel(nucleus);nucleus.setParameter("kernel",kernel);service=new Fixture(new Transport());kernel.installModule("AIGC",service);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public void cellInitialized(Nucleus nucleus){kernel.startup();}
    public void cellPredestroy(Nucleus nucleus){kernel.dispose();}
    public void cellDestroyed(Nucleus nucleus){kernel.shutdown();hardware.shutdownNow();}
}
