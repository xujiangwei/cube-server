package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** User-language expectations and adversarial scope mutations; no external model or service required. */
public final class RetrievalTest {
    private static final Instant NOW=Instant.parse("2026-11-17T10:00:00Z");
    private static JSONObject config;
    private static int assertions;
    static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    static JSONObject input(String... texts) {
        JSONArray messages=new JSONArray();for(String text:texts)messages.put(new JSONObject().put("kind","instruction").put("receivedAt",NOW.toEpochMilli()).put("input",new JSONObject().put("instruction",text).put("attachmentIds",new JSONArray())));
        return new JSONObject().put("inputRevision",texts.length-1).put("createdAt",NOW.toEpochMilli()).put("messages",messages);
    }
    static JSONObject plan(JSONObject conf,JSONObject input){return RetrievalPlanner.plan(conf,conf.getJSONObject("regions").getJSONArray("items"),input,new JSONArray(),NOW);}
    static JSONObject plan(String...text){return plan(config,input(text));}
    static JSONObject ready(String...text){JSONObject p=plan(text);check(p.getString("status").equals("ready"),p.toString());return p.getJSONObject("task");}
    static void asks(String text,String field){JSONObject p=plan(text);check(p.getString("status").equals("needs_input")&&p.getJSONArray("issues").toString().contains("\"field\":\""+field+"\""),p.toString());}
    interface Action {void run()throws Exception;}
    static void fails(String code,Action action)throws Exception {try{action.run();throw new AssertionError(code);}catch(StoreException error){check(code.equals(error.code),code+" != "+error.code);}}
    static JSONObject copy(){return new JSONObject(config.toString());}
    static JSONObject item(String media,String start,String end,String region) {
        JSONObject row=new JSONObject().put("id","asset-1").put("version","v1").put("title","受控资源").put("mediaType",media).put("regionId",region).put("summary","来自业务接口")
                .put("createdAt",start).put("startedAt",start).put("endedAt",end).put("status","published");
        return new JSONObject().put("data",new JSONObject().put("items",new JSONArray().put(row)));
    }
    public static void main(String[] args)throws Exception {
        config=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0]).resolve("retrieval-profiles.json")),"UTF-8"));RetrievalConfig.validate(config);
        JSONObject task=ready("调出今天下午3点到4点的厂区1内的巡检录像");
        check(task.getString("intent").equals("retrieve")&&task.getString("resourceType").equals("video"),"retrieve is not analyze");
        check(task.getJSONObject("time").getString("from").equals("2026-11-17T15:00:00+08:00")&&task.getJSONObject("time").getString("to").equals("2026-11-17T16:00:00+08:00"),"afternoon carries to second endpoint");
        check(task.getJSONObject("region").getString("id").equals("plant-1")&&task.getJSONObject("region").getJSONArray("allowedIds").toList().contains("plant-1-workshop"),"catalog ID and descendants");
        check(ready("查找昨天15:00到16:00一号厂区的图片").getString("profileId").equals("patrol-images"),"image route and region alias");
        JSONObject doc=ready("检索昨天厂区1已发布的关于“安全”的文档");
        check(doc.getString("keywords").equals("安全")&&doc.getJSONObject("filters").getString("status").equals("published"),"document keywords and configured enum");
        check(ready("调出今天下午3点到4点厂区1的录像","改成下午四点到五点，其他不变").getJSONObject("time").getString("to").contains("17:00:00"),"partial correction keeps date and region");
        check(ready("调出今天下午3点到4点厂区1的录像","改成图片，其他不变").getString("resourceType").equals("image"),"resource type correction");
        check(ready("查询今天下午3点到4点厂区1车间的图片").getJSONObject("region").getString("id").equals("plant-1-workshop"),"longest region name avoids parent substring");
        check(ready("查询昨晚11点到今天凌晨1点厂区1的录像").getJSONObject("time").getString("from").equals("2026-11-16T23:00:00+08:00"),"explicit midnight range");
        check(ready("查看过去两小时厂区1的录像").getJSONObject("time").getString("from").equals("2026-11-17T16:00:00+08:00"),"rolling hours use receipt clock");
        check(ready("查询今天厂区1的图片","改成昨天，其他不变").getJSONObject("time").getString("to").equals("2026-11-17T00:00:00+08:00"),"whole-day correction preserves exclusive next-day endpoint");
        check(ready("查询今天厂区1的图片").getJSONObject("time").getString("to").equals("2026-11-17T18:00:00+08:00"),"today without clocks ends at the current planning instant");
        check(ready("查询昨天厂区1关于“不要吸烟ABC”的文档").getString("keywords").equals("不要吸烟ABC"),"quoted terms retain case and do not become operations");
        check(ready("查询昨天厂区1关于“取消关键词”的文档").getString("keywords").equals("取消关键词"),"quoted clear command remains a literal keyword");
        asks("查询今天下午十二三点到四点厂区1的录像","time");
        asks("查询今天晚上12点到1点厂区1的录像","time");
        asks("查询今天中午十点到十一点厂区1的录像","time");
        asks("查询今天下午零点到一点厂区1的录像","time");
        asks("查询今天昨天前天下午3点到4点厂区1的录像","time");
        asks("查询今天3点到4点厂区1的录像","time");
        asks("查询今天下午4点到3点厂区1的录像","time");
        asks("查询今天下午3点到4点未知厂区的录像","region");
        asks("查询明天下午3点到4点厂区1的录像","time");
        asks("查询2026年2月30日下午3点到4点厂区1的录像","time");
        asks("查询今天下午3点到4点厂区1的4K录像","scope");
        check(plan("查询今天下午3点到4点厂区1的4K录像").getJSONObject("task").getString("resourceType").equals("video"),"unsupported resolution does not hide the video type");
        asks("分析今天下午3点到4点厂区1的录像","scope");
        asks("查询今天下午3点到4点厂区1的录像，不要图片","scope");
        asks("查询今天下午3点到4点厂区10的录像","region");
        asks("查询今天下午3点到4点厂区1和厂区2的录像","region");
        JSONObject unsupported=copy();unsupported.getJSONArray("profiles").remove(1);
        check(plan(unsupported,input("查询今天下午3点到4点厂区1的录像")).getString("status").equals("needs_input"),"never substitute images for unavailable video route");
        JSONObject delayed=input("查询今天下午3点到4点厂区1的录像");delayed.getJSONArray("messages").getJSONObject(0).put("receivedAt",NOW.minus(Duration.ofDays(1)).toEpochMilli());
        check(plan(config,delayed).getJSONObject("task").getJSONObject("time").getString("from").startsWith("2026-11-16"),"today does not change after restart or waiting");
        JSONObject dst=copy();dst.put("zone","America/New_York");
        check(plan(dst,input("查询2026-11-01 01:15到01:45厂区1的录像")).getJSONArray("issues").toString().contains("夏令时"),"ambiguous DST time must be clarified");
        JSONObject iso=plan(dst,input("查询2026-11-01T01:15:00-04:00到2026-11-01T01:45:00-04:00厂区1的录像"));
        check(iso.getString("status").equals("ready"),"explicit offsets disambiguate DST: "+iso);
        JSONObject ambiguous=copy();ambiguous.getJSONObject("regions").getJSONArray("items").getJSONObject(2).getJSONArray("aliases").put("厂区1");
        check(plan(ambiguous,input("查询今天下午3点到4点厂区1的录像")).getJSONArray("questions").toString().contains("plant-2"),"ambiguous alias offers actual catalog choices");
        JSONObject video=RetrievalConfig.profile(config,"patrol-videos");
        check(RetrievalProvider.project(item("video/mp4","2026-11-17T14:50:00+08:00","2026-11-17T15:10:00+08:00","plant-1-workshop"),video.getJSONObject("response"),video,task).length()==1,"video interval overlap, not capture-point filtering");
        fails("RETRIEVAL_SCOPE_VIOLATION",()->RetrievalProvider.project(item("video/mp4","2026-11-17T14:50:00+08:00","2026-11-17T15:00:00+08:00","plant-1"),video.getJSONObject("response"),video,task));
        fails("RETRIEVAL_SCOPE_VIOLATION",()->RetrievalProvider.project(item("video/mp4","2026-11-17T15:00:00+08:00","2026-11-17T15:10:00+08:00","plant-2"),video.getJSONObject("response"),video,task));
        fails("RETRIEVAL_MEDIA_TYPE_UNSUPPORTED",()->RetrievalProvider.project(item("image/jpeg","2026-11-17T15:00:00+08:00","2026-11-17T15:10:00+08:00","plant-1"),video.getJSONObject("response"),video,task));
        JSONObject document=RetrievalConfig.profile(config,"documents"),draft=item("application/pdf","2026-11-16T15:00:00+08:00","","plant-1");
        draft.getJSONObject("data").getJSONArray("items").getJSONObject(0).put("status","draft");
        fails("RETRIEVAL_SCOPE_VIOLATION",()->RetrievalProvider.project(draft,document.getJSONObject("response"),document,doc));
        JSONObject bad=copy();bad.getJSONArray("profiles").getJSONObject(0).getJSONObject("request").getJSONObject("query").put("url","${args.url}");
        fails("RETRIEVAL_CONFIG_INVALID",()->RetrievalConfig.validate(bad));
        JSONObject cycle=copy();cycle.getJSONObject("regions").getJSONArray("items").getJSONObject(0).put("parentId","plant-1-workshop");
        fails("RETRIEVAL_CONFIG_INVALID",()->RetrievalConfig.validate(cycle));
        new RetrievalProvider(new Properties()).validate(new JSONObject().put("action","plan").put("skill","my-business-system/resource-retrieval"));
        workflow(Paths.get(args[0]),task);
        System.out.println("PASS retrieval: "+assertions+" language, time, alias, semantic, response-scope and workflow assertions.");
    }
    static void workflow(Path directory,JSONObject task)throws Exception {
        CompletionGuard.Resources resources=new CompletionGuard.Resources(){public JSONObject json(String path)throws Exception{return new JSONObject(new String(Files.readAllBytes(directory.resolve(path)),"UTF-8"));}};
        WorkflowGuard guard=new WorkflowGuard(new Properties());guard.validate(resources);
        check(guard.advance(resources,new JSONArray(),"business/retrieval").getJSONObject("call").getString("tool").equals("common.retrieval.query"),"host starts structured planning");
        JSONObject plan=new JSONObject().put("status","needs_input").put("planRef","p1").put("skill","business/retrieval").put("task",task)
                .put("question","请选择区域").put("questions",new JSONArray().put(RetrievalPlanner.question("region","请选择区域",new JSONArray().put(RetrievalPlanner.option("plant-1","厂区1")))));
        JSONArray evidence=new JSONArray().put(new JSONObject().put("kind","retrieval-plan").put("operation","common.retrieval.query.v1").put("value",plan));
        check(guard.advance(resources,evidence,"business/retrieval").getString("kind").equals("need_input"),"clarification before network search");
        check(!guard.check(resources,evidence).getBoolean("satisfied"),"cannot claim success while missing input");
        plan.put("status","ready");
        check(new JSONObject(guard.advance(resources,evidence,"business/retrieval").getJSONObject("call").getString("argumentsJson")).getString("action").equals("search"),"ready plan advances search");
        JSONObject page=new JSONObject().put("skill","business/retrieval").put("planRef","p1").put("cursor","").put("nextCursor","opaque-next").put("snapshotId","snapshot-1").put("items",new JSONArray());
        evidence.put(new JSONObject().put("kind","retrieval-page").put("operation","common.retrieval.query.v1").put("value",page));
        check(new JSONObject(guard.advance(resources,evidence,"business/retrieval").getJSONObject("call").getString("argumentsJson")).getString("cursor").equals("opaque-next"),"short empty page with a cursor is not complete");
        JSONObject last=new JSONObject(page.toString()).put("cursor","opaque-next").put("nextCursor","");
        evidence.put(new JSONObject().put("kind","retrieval-page").put("operation","common.retrieval.query.v1").put("value",last));
        check(guard.check(resources,evidence).getJSONObject("retrieval").getJSONObject("coverage").getInt("totalResources")==0,"confirmed empty coverage is a real result");
        check(guard.advance(resources,evidence,"business/retrieval").getString("kind").equals("final"),"host creates final metadata only after complete page chain");
        last.put("snapshotId","changed");fails("RETRIEVAL_SNAPSHOT_CONFLICT",()->guard.advance(resources,evidence,"business/retrieval"));
    }
}
