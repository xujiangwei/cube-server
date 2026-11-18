package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.util.*;

/** Host-driven plan -> clarification -> authorized pages -> grounded metadata delivery. */
final class RetrievalWorkflow {
    static final String VERSION="resource-retrieval-v1";
    static boolean active(CompletionGuard.Resources resources)throws Exception {
        JSONObject execution=resources.json(CompletionGuard.FILE).optJSONObject("execution");return execution!=null&&VERSION.equals(execution.optString("version"));
    }
    static void validate(CompletionGuard.Resources resources)throws Exception {
        JSONObject requirements=resources.json(CompletionGuard.FILE);
        ConnectorConfig.keys(requirements,"version,maxRepairs,execution", "");
        RetrievalConfig.require(requirements.getString("version").equals("1"));RetrievalConfig.integer(requirements,"maxRepairs",0,2);
        ConnectorConfig.keys(requirements.getJSONObject("execution"),"version", "");
        RetrievalConfig.require(requirements.getJSONObject("execution").getString("version").equals(VERSION));RetrievalConfig.validate(resources.json(RetrievalConfig.FILE));
    }
    private static JSONObject call(String skill,String action,String plan,String cursor) {
        JSONObject args=new JSONObject().put("skill",skill).put("action",action);
        if(plan!=null)args.put("planRef",plan);if(cursor!=null&&!cursor.isEmpty())args.put("cursor",cursor);
        return new JSONObject().put("kind","tool_call").put("call",new JSONObject().put("tool","common.retrieval.query")
                .put("proposalId","retrieval-"+RuntimeSupport.sha(RuntimeSupport.bytes(args))).put("argumentsJson",args.toString()));
    }
    private static JSONObject plan(JSONArray evidence) {
        JSONObject result=null;
        for(Object f:evidence) {JSONObject fact=(JSONObject)f;if(!fact.getString("kind").equals("retrieval-plan")||!fact.getString("operation").equals("common.retrieval.query.v1"))continue;
            if(result!=null&&!result.getString("planRef").equals(fact.getJSONObject("value").getString("planRef")))throw new StoreException("RETRIEVAL_PLAN_CONFLICT");result=fact.getJSONObject("value");}
        return result;
    }
    private static JSONObject walk(CompletionGuard.Resources resources,JSONArray evidence,String skill,boolean advance)throws Exception {
        JSONObject plan=plan(evidence);
        if(plan==null)return advance?call(skill,"plan",null,null):null;
        if(!plan.getString("status").equals("ready"))return advance?new JSONObject().put("kind","need_input").put("binding","retrieval:"+plan.getString("planRef"))
                .put("question",plan.getString("question")).put("questions",plan.getJSONArray("questions")).put("retrievalPlanRef",plan.getString("planRef")):null;
        JSONObject task=plan.getJSONObject("task"),profile=RetrievalConfig.profile(resources.json(RetrievalConfig.FILE),task.getString("profileId"));
        Map<String,JSONObject> pages=new HashMap<>();int total=0;
        for(Object f:evidence) {JSONObject fact=(JSONObject)f;if(!fact.getString("kind").equals("retrieval-page")||!fact.getString("operation").equals("common.retrieval.query.v1"))continue;
            JSONObject value=fact.getJSONObject("value");if(!value.getString("planRef").equals(plan.getString("planRef")))continue;
            if(pages.put(value.getString("cursor"),value)!=null)throw new StoreException("RETRIEVAL_PAGE_CONFLICT");total++;
        }
        String cursor="",snapshot=null;Set<String> seen=new HashSet<>();Map<String,JSONObject> items=new LinkedHashMap<>();
        do {
            if(!seen.add(cursor))throw new StoreException("RETRIEVAL_CURSOR_REPEATED");
            if(seen.size()>profile.getInt("maxPages"))throw new StoreException("RETRIEVAL_PAGE_LIMIT");
            JSONObject page=pages.get(cursor);
            if(page==null)return advance?call(skill,"search",plan.getString("planRef"),cursor):null;
            if(snapshot==null)snapshot=page.getString("snapshotId");else if(!snapshot.equals(page.getString("snapshotId")))throw new StoreException("RETRIEVAL_SNAPSHOT_CONFLICT");
            for(Object entry:page.getJSONArray("items")) {
                JSONObject item=(JSONObject)entry;String key=new JSONArray().put(item.getString("id")).put(item.getString("version")).toString();
                JSONObject old=items.putIfAbsent(key,item);if(old!=null&&!old.similar(item))throw new StoreException("RETRIEVAL_RESULT_CONFLICT");
            }
            cursor=page.getString("nextCursor");
        }while(!cursor.isEmpty());
        if(total!=seen.size())throw new StoreException("RETRIEVAL_PAGE_CONFLICT");
        JSONObject publicTask=new JSONObject(task.toString());if(publicTask.has("region"))publicTask.getJSONObject("region").remove("allowedIds");
        JSONObject delivery=new JSONObject().put("schemaVersion",1).put("format",VERSION).put("skill",skill).put("task",publicTask)
                .put("resources",new JSONArray(items.values())).put("coverage",new JSONObject().put("complete",true).put("snapshotId",snapshot).put("pages",total).put("totalResources",items.size()));
        if(RuntimeSupport.bytes(delivery).length>262144)throw new StoreException("RETRIEVAL_RESULT_LIMIT");
        return delivery;
    }
    static JSONObject advance(CompletionGuard.Resources resources,JSONArray evidence,String skill)throws Exception {
        JSONObject result=walk(resources,evidence,skill,true);
        return (result.has("kind")?result:new JSONObject().put("kind","final").put("text",result.toString()).put("construction",VERSION)).put("adoptsInput",true);
    }
    static JSONObject check(CompletionGuard.Resources resources,JSONArray evidence)throws Exception {
        String skill="";for(Object e:evidence)if(((JSONObject)e).getJSONObject("value").has("skill")) {skill=((JSONObject)e).getJSONObject("value").getString("skill");break;}
        JSONObject delivery=walk(resources,evidence,skill,false);boolean satisfied=delivery!=null;
        JSONObject result=new JSONObject().put("satisfied",satisfied).put("maxRepairs",resources.json(CompletionGuard.FILE).getInt("maxRepairs"))
                .put("missingCount",satisfied?0:1).put("missing",satisfied?new JSONArray():new JSONArray().put(new JSONObject().put("code","RETRIEVAL_INCOMPLETE")));
        if(satisfied)result.put("retrieval",delivery);return result;
    }
}
