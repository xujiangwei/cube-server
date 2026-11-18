package cube.service.harness.skill;

import cube.service.harness.runtime.RuntimeSupport;
import org.json.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Builds a typed task from original user messages, never from model-authored parameter values. */
final class RetrievalPlanner {
    private static final Pattern QUOTED=Pattern.compile("[“\"]([^”\"]{1,256})[”\"]");
    private static final Pattern UNSUPPORTED=Pattern.compile("分析|识别|统计|剪辑|删除|修改文件|下载|播放|跟踪|告警|排除|除了|不要|不包含");
    private static final String[] BUILTIN_TYPES={"录像","视频","图片","照片","文档","文件","音频","录音"};
    private static final String NOISE="请帮我|麻烦帮我|只做检索|只检索|仅检索|调出来|调出|调取|查找|查询|检索|找出|搜索|查看|列出|获取|给我|帮我|找一下|看一下|一下|其他不变|其它不变|其余不变|改成|改为|调整为|保持|按以下回答继续|关键词是|关键字是|关键词|关键字|关于|有关|明确范围|只要|所有|全部|以及|还有|并且|时间范围|区域|范围|从|到|至|在|的|内|里|中|和|及|请|把|将|是";

    static JSONObject question(String id,String prompt,JSONArray options) {
        return new JSONObject().put("questionId","retrieval."+id).put("prompt",prompt).put("options",options).put("allowFreeText",true);
    }
    static JSONObject option(String id,String label) {
        return new JSONObject().put("id",id).put("label",Budget.prefix(label,120));
    }
    static void issue(JSONArray issues,JSONArray questions,String id,String prompt,JSONArray options) {
        if(issues.length()>=3)return;
        issues.put(new JSONObject().put("field",id).put("code","RETRIEVAL_CLARIFICATION_REQUIRED").put("message",prompt));
        questions.put(question(id,prompt,options));
    }
    private static List<String> names(JSONObject o,String name) {
        List<String> result=new ArrayList<>();result.add(o.getString(name));if(o.has("aliases"))for(Object a:o.getJSONArray("aliases"))result.add(a.toString());
        result.sort((a,b)->Integer.compare(b.length(),a.length()));return result;
    }
    private static boolean boundary(String text,int begin,int end) {
        return !(begin>0&&ascii(text.charAt(begin))&&ascii(text.charAt(begin-1)))
                &&!(end<text.length()&&ascii(text.charAt(end-1))&&ascii(text.charAt(end)));
    }
    private static boolean ascii(char c) {return c<128&&(Character.isLetterOrDigit(c)||c=='_'||c=='-');}
    private static List<int[]> occurrences(String text,String name) {
        List<int[]> result=new ArrayList<>();String n=RetrievalConfig.normal(name);
        for(int i=0;(i=text.indexOf(n,i))>=0;i+=Math.max(1,n.length()))if(boundary(text,i,i+n.length()))result.add(new int[]{i,i+n.length()});
        return result;
    }
    private static String blank(String text,int start,int end) {StringBuilder b=new StringBuilder(text);for(int i=start;i<end;i++)b.setCharAt(i,' ');return b.toString();}
    private static final class Matches {
        final Set<String> ids=new LinkedHashSet<>();String remainder;
        Matches(String text,JSONArray entries,String name) {
            remainder=text;List<Object[]> found=new ArrayList<>();
            for(Object entry:entries) {
                JSONObject object=(JSONObject)entry;
                for(String alias:names(object,name))for(int[] span:occurrences(text,alias))found.add(new Object[]{object.getString("id"),span[0],span[1]});
            }
            found.sort((a,b)->Integer.compare((Integer)b[2]-(Integer)b[1],(Integer)a[2]-(Integer)a[1]));
            List<int[]> kept=new ArrayList<>();
            for(Object[] hit:found) {
                int start=(Integer)hit[1],end=(Integer)hit[2];boolean contained=false;
                for(int[] prior:kept)if(start>=prior[0]&&end<=prior[1]&&(start!=prior[0]||end!=prior[1]))contained=true;
                if(contained)continue;ids.add((String)hit[0]);kept.add(new int[]{start,end});remainder=blank(remainder,start,end);
            }
        }
    }

    static JSONObject plan(JSONObject config,JSONArray regions,JSONObject input,JSONArray selections,Instant now) {
        RetrievalConfig.validate(config);RetrievalConfig.catalog(regions);
        JSONArray profiles=config.getJSONArray("profiles"),messages=input.getJSONArray("messages");
        RetrievalTime time=new RetrievalTime(config.getString("zone"));
        Set<String> profileIds=new LinkedHashSet<>(),regionIds=new LinkedHashSet<>();
        Map<String,String> filters=new TreeMap<>();String keywords="",unparsed="",intentIssue="";boolean explicitType=false;
        List<String> remainders=new ArrayList<>();
        for(int index=0;index<messages.length();index++) {
            JSONObject message=messages.getJSONObject(index);
            String original=java.text.Normalizer.normalize(message.getJSONObject("input").getString("instruction"),java.text.Normalizer.Form.NFKC).trim();
            String text=RetrievalConfig.normal(original);
            JSONObject selection=index<selections.length()?selections.optJSONObject(index):null;
            if(selection!=null&&selection.optBoolean("resetScope")) {
                unparsed="";remainders.clear();intentIssue="";filters.clear();keywords="";
                profileIds.clear();regionIds.clear();explicitType=false;time=new RetrievalTime(config.getString("zone"));
            }
            Matcher quoted=QUOTED.matcher(original);List<String> terms=new ArrayList<>();String masked=original;
            while(quoted.find()) {terms.add(quoted.group(1));masked=blank(masked,quoted.start(),quoted.end());}
            masked=RetrievalConfig.normal(masked);
            if(masked.contains("只检索")||masked.contains("仅检索")||masked.contains("只做检索"))intentIssue="";
            Matcher operation=UNSUPPORTED.matcher(masked);if(operation.find())intentIssue="当前检索工具不能执行“"+operation.group()+"”。请重新提供完整检索条件，只包含已提供的检索能力。";
            if(!terms.isEmpty()) {keywords=String.join(" ",terms);if(keywords.length()>256)unparsed="关键词过长，请缩小至256字符以内。";}
            if(masked.contains("取消关键词")||masked.contains("清除关键词")) {keywords="";masked=masked.replace("取消关键词", "").replace("清除关键词", "");}
            String timed=time.apply(masked,message.getLong("receivedAt"));
            Matches types=new Matches(timed,profiles,"label");
            if(!types.ids.isEmpty()) {profileIds=types.ids;explicitType=true;}
            else for(String type:BUILTIN_TYPES)if(timed.contains(type)) {profileIds.clear();explicitType=true;}
            Matches locations=new Matches(types.remainder,regions,"name");
            if(!locations.ids.isEmpty())regionIds=locations.ids;
            masked=locations.remainder;
            // A known but unavailable type is resolved by the explicit profile question.
            if(types.ids.isEmpty())for(String type:BUILTIN_TYPES)masked=masked.replace(type, "");
            for(Object entry:profiles) {
                JSONObject p=(JSONObject)entry;JSONArray fs=p.optJSONArray("filters");if(fs==null)continue;
                for(Object f:fs) {
                    JSONObject filter=(JSONObject)f;Matches values=new Matches(masked,filter.getJSONArray("values"),"label");
                    if(!values.ids.isEmpty()) {filters.put(filter.getString("id"),values.ids.size()==1?values.ids.iterator().next():"!");masked=values.remainder;}
                }
            }
            if(selection!=null) {
                if(selection.has("profileId")) {profileIds=new LinkedHashSet<>(Collections.singleton(selection.getString("profileId")));explicitType=true;}
                if(selection.has("regionId"))regionIds=new LinkedHashSet<>(Collections.singleton(selection.getString("regionId")));
                JSONObject selectedFilters=selection.optJSONObject("filters");if(selectedFilters!=null)for(String key:selectedFilters.keySet())filters.put(key,selectedFilters.getString(key));
            }
            // Business vocabulary describes a route; it cannot introduce new predicates or code.
            for(Object entry:profiles) {
                JSONArray words=((JSONObject)entry).optJSONArray("vocabulary");if(words!=null)for(Object word:words)masked=masked.replace(RetrievalConfig.normal(word.toString()), "");
            }
            masked=masked.replaceAll(NOISE," ").replaceAll("[\\s\\p{Punct}，。！？；：、（）【】～—]+", "");
            if(!masked.isEmpty())remainders.add(masked);
        }
        if(profileIds.isEmpty()&&!explicitType&&profiles.length()==1)profileIds.add(profiles.getJSONObject(0).getString("id"));
        JSONObject task=new JSONObject().put("schemaVersion",1).put("intent","retrieve").put("inputRevision",input.getLong("inputRevision"))
                .put("inputDigest",RuntimeSupport.sha(RuntimeSupport.bytes(input))).put("keywords",keywords).put("filters",new JSONObject());
        JSONArray issues=new JSONArray(),questions=new JSONArray(),sourceRefs=new JSONArray();
        JSONObject regionConfig=config.getJSONObject("regions");if(regionConfig.getString("kind").equals("http"))sourceRefs.put(regionConfig.getString("sourceRef"));
        JSONObject profile=null;
        if(profileIds.size()!=1) {
            JSONArray options=new JSONArray();for(Object entry:profiles) {JSONObject p=(JSONObject)entry;options.put(option(p.getString("id"),p.getString("label")));}
            issue(issues,questions,"profile","请选择一种已配置的检索资源类型。",options);
        } else {
            profile=RetrievalConfig.profile(config,profileIds.iterator().next());sourceRefs.put(profile.getString("sourceRef"));
            task.put("profileId",profile.getString("id")).put("resourceType",profile.getString("resourceType"));
            JSONObject interval=time.resolve(profile.getJSONObject("time"),now);
            if(interval!=null)task.put("time",interval);
            if(!time.issue.isEmpty())issue(issues,questions,"time",time.issue,new JSONArray());
            if(regionIds.size()>1||profile.getBoolean("regionRequired")&&regionIds.isEmpty()) {
                JSONArray options=new JSONArray();for(Object entry:regions) {
                    JSONObject r=(JSONObject)entry;if(regionIds.isEmpty()||regionIds.contains(r.getString("id")))options.put(option(r.getString("id"),r.getString("name")+" ("+r.getString("id")+")"));
                }
                if(options.length()>8)options=new JSONArray();
                issue(issues,questions,"region",regionIds.size()>1?"区域名称匹配多个业务区域，请选择确切区域。":"请提供要检索的业务区域。",options);
            } else if(regionIds.size()==1) {
                String selected=regionIds.iterator().next();JSONObject region=null;
                for(Object entry:regions)if(((JSONObject)entry).getString("id").equals(selected))region=(JSONObject)entry;
                if(region==null)issue(issues,questions,"region","该区域当前不可用，请重新选择获准区域。",new JSONArray());
                else task.put("region",new JSONObject().put("id",selected).put("name",region.getString("name"))
                        .put("scope",profile.getString("regionScope")).put("allowedIds",new JSONArray(RetrievalConfig.descendants(regions,selected,profile.getString("regionScope").equals("subtree")))));
            }
            Set<String> mapped=new HashSet<>();for(Object value:profile.getJSONObject("request").getJSONObject("query").toMap().values())mapped.add(value.toString());
            if(!keywords.isEmpty()&&!mapped.contains("${args.query}"))unparsed="该检索档案不支持关键词筛选，不能忽略这个条件。";
            if(regionIds.size()==1&&!mapped.contains("${args.regionId}"))unparsed="该检索档案不支持区域筛选，不能忽略这个条件。";
            JSONArray fs=profile.optJSONArray("filters");Set<String> allowedFilters=new HashSet<>();
            if(fs!=null)for(Object f:fs) {
                JSONObject filter=(JSONObject)f;String fid=filter.getString("id");allowedFilters.add(fid);String selected=filters.get(fid);
                if(selected==null&&!filter.getBoolean("required"))continue;
                JSONObject chosen=null;JSONArray options=new JSONArray();for(Object v:filter.getJSONArray("values")) {JSONObject value=(JSONObject)v;options.put(option(value.getString("id"),value.getString("label")));if(value.getString("id").equals(selected))chosen=value;}
                if(chosen==null)issue(issues,questions,"filter_"+fid,"请选择“"+filter.getString("label")+"”的值。",options.length()<=8?options:new JSONArray());
                else if(!mapped.contains("${args.filter_"+fid+"}"))unparsed="检索档案未映射筛选参数："+fid;
                else task.getJSONObject("filters").put(fid,selected);
            }
            for(String fid:filters.keySet())if(!allowedFilters.contains(fid))unparsed="当前资源类型不支持此前的筛选条件，请重新明确完整检索范围。";
        }
        if(!intentIssue.isEmpty())issue(issues,questions,"scope",intentIssue,new JSONArray());
        else {
            if(unparsed.isEmpty()&&!remainders.isEmpty())unparsed="以下条件尚不能可靠解析："+Budget.prefix(String.join("；",remainders),160)+"。请用明确的日期、时段、区域、资源类型及带引号的关键词重新描述。";
            if(!unparsed.isEmpty())issue(issues,questions,"scope",unparsed,new JSONArray());
        }
        return new JSONObject().put("status",issues.length()==0?"ready":"needs_input").put("task",task)
                .put("issues",issues).put("questions",questions).put("question",issues.length()==0?"检索范围已校验。":"请补充或确认检索条件。")
                .put("sourceRefs",sourceRefs);
    }
}
