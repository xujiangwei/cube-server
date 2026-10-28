package cube.console.mgmt;

import cube.aigc.usage.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Read-only federation of module-owned accounting stores. Never creates or upgrades tables. */
public final class AIUsageManager {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String[] METRICS={"calls","actual_calls","estimated_calls","unknown_calls","pending_calls","input_tokens","output_tokens","estimated_input","estimated_output"};
    private final JSONArray sources;
    private final String configError;

    public AIUsageManager(Properties p) {
        JSONArray parsed=new JSONArray();String error=null;
        Path file=Paths.get(p.getProperty("ai.usage.sources","config/ai-usage-sources.json"));
        try {if(Files.isRegularFile(file)) parsed=new JSONArray(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));}
        catch(Exception e){error="invalid_configuration";}
        sources=parsed;configError=error;
    }
    public AIUsageManager(JSONArray sources){this.sources=new JSONArray(sources.toString());configError=null;}

    public static final class Filter {
        public long from,to;public String domain="",model="",module="",unit="",source="",group="model",grain="day",month="";
        public Long contact;public int offset=0,limit=50;
        public Filter(Map<String,String> p,User user){
            ZonedDateTime now=ZonedDateTime.now(ZONE);
            from=parseLong(p,"from",now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli());
            to=parseLong(p,"to",System.currentTimeMillis()+1);
            domain=p.getOrDefault("domain","");model=p.getOrDefault("model","");module=p.getOrDefault("module","");unit=p.getOrDefault("unit","");source=p.getOrDefault("source","");
            if(p.containsKey("contact")&&!p.get("contact").isEmpty()){contact=Long.valueOf(p.get("contact"));if(contact<=0)throw new IllegalArgumentException("Invalid Contact");}
            month=p.getOrDefault("month","");
            if(!month.isEmpty()) {YearMonth m=YearMonth.parse(month);from=m.atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();to=m.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();}
            if(user==null)throw new SecurityException("Sign in required");
            if(user.role!=1){if(user.group==null||user.group.isEmpty()||(!domain.isEmpty()&&!domain.equals(user.group)))throw new SecurityException("Domain access denied");domain=user.group;}
            if(from<0||to<=from||to-from>366L*86400000)throw new IllegalArgumentException("Time range must be within 366 days");
            offset=Math.toIntExact(parseLong(p,"offset",0));limit=Math.toIntExact(parseLong(p,"limit",50));
            if(offset<0||offset>1000000||limit<1||limit>500)throw new IllegalArgumentException("Invalid pagination");
            group=p.getOrDefault("group","model");grain=p.getOrDefault("grain","day");
            if(!Arrays.asList("model","module","contact","unit").contains(group)||!Arrays.asList("hour","day").contains(grain))throw new IllegalArgumentException("Invalid grouping");
        }
        private static long parseLong(Map<String,String> p,String key,long fallback){String v=p.get(key);return v==null||v.isEmpty()?fallback:Long.parseLong(v);}
        JSONObject json(){return new JSONObject().put("from",from).put("to",to).put("domain",domain).put("contact",contact==null?JSONObject.NULL:contact).put("model",model).put("module",module).put("unit",unit).put("source",source).put("month",month);}
    }
    private Connection connect(JSONObject s)throws Exception{
        if("sqlite".equals(s.getString("type"))){
            Path file=Paths.get(s.getString("file"));if(!file.isAbsolute()||!Files.isRegularFile(file))throw new SQLException("Database unavailable");
            Connection c=DriverManager.getConnection("jdbc:sqlite:file:"+file.toUri().getRawPath()+"?mode=ro");
            try(Statement q=c.createStatement()){q.execute("PRAGMA query_only=ON");q.execute("PRAGMA busy_timeout=5000");}return c;
        }
        if(!"mysql".equals(s.getString("type")))throw new SQLException("Unsupported source");
        String host=s.getString("host"),database=s.getString("database");int port=s.optInt("port",3306);
        if(!host.matches("[A-Za-z0-9.-]+")||!database.matches("[A-Za-z0-9_]+")||port<1||port>65535)throw new SQLException("Invalid source");
        String password=s.has("passwordEnv")?System.getenv(s.getString("passwordEnv")):s.optString("password","");
        Connection c=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/"+database+"?useUnicode=true&characterEncoding=utf8&connectTimeout=5000&socketTimeout=15000&serverTimezone=UTC",s.getString("user"),password);
        c.setReadOnly(true);return c;
    }
    private static final class Where {
        final List<Object> args=new ArrayList<>();final StringBuilder sql=new StringBuilder(" WHERE contact_id>0");
        Where(Filter f,String time,long from,long to){
            if ("started_at".equals(time)) sql.append(" AND status<>'not_dispatched'");
            sql.append(" AND ").append(time).append(">=? AND ").append(time).append("<?");args.add(from);args.add(to);
            add("domain",f.domain);add("model",f.model);add("module",f.module);add("unit_id",f.unit);
            if(f.contact!=null){sql.append(" AND contact_id=?");args.add(f.contact);}
        }
        void add(String col,String value){if(!value.isEmpty()){sql.append(" AND ").append(col).append("=?");args.add(value);}}
    }
    private static String aggregate(boolean hourly){
        if(hourly){StringJoiner s=new StringJoiner(",");for(String m:METRICS)s.add("COALESCE(SUM("+m+"),0) AS "+m);return s.toString();}
        return "COUNT(*) AS calls,SUM(CASE WHEN quality='actual' THEN 1 ELSE 0 END) AS actual_calls,SUM(CASE WHEN quality='estimated' THEN 1 ELSE 0 END) AS estimated_calls,SUM(CASE WHEN quality='unknown' THEN 1 ELSE 0 END) AS unknown_calls,SUM(CASE WHEN quality='pending' OR (quality='actual' AND status='unknown') THEN 1 ELSE 0 END) AS pending_calls,"
                +"SUM(CASE WHEN quality='actual' THEN input_tokens ELSE 0 END) AS input_tokens,SUM(CASE WHEN quality='actual' THEN output_tokens ELSE 0 END) AS output_tokens,SUM(CASE WHEN quality='estimated' THEN input_tokens ELSE 0 END) AS estimated_input,SUM(CASE WHEN quality='estimated' THEN output_tokens ELSE 0 END) AS estimated_output";
    }
    private static String groupColumns(String group){return "contact".equals(group)?"domain,contact_id":"unit".equals(group)?"unit_id":group;}
    private static String bucket(String time,Filter f){long span="hour".equals(f.grain)?3600000:86400000;long shift="hour".equals(f.grain)?0:28800000;return "("+time+"-(("+time+"+"+shift+")%"+span+"))";}
    private JSONArray grouped(Connection c,String prefix,Filter f,String group)throws SQLException{
        long start=(f.from+3599999)/3600000*3600000,end=f.to/3600000*3600000;
        List<JSONObject> rows=new ArrayList<>();
        if(start>=end)append(rows,c,prefix,f,group,false,f.from,f.to);
        else{append(rows,c,prefix,f,group,true,start,end);if(f.from<start)append(rows,c,prefix,f,group,false,f.from,start);if(end<f.to)append(rows,c,prefix,f,group,false,end,f.to);}
        return merge(rows,group);
    }
    private void append(List<JSONObject> result,Connection c,String prefix,Filter f,String group,boolean hourly,long from,long to)throws SQLException{
        String time=hourly?"hour_at":"started_at",columns=group.isEmpty()?"":"trend".equals(group)?bucket(time,f)+" AS timestamp":groupColumns(group);
        Where w=new Where(f,time,from,to);
        JSONArray rows=UsageLedger.rows(c,"SELECT "+(columns.isEmpty()?"":columns+",")+aggregate(hourly)+" FROM "+prefix+(hourly?"hourly":"call")+w.sql+(columns.isEmpty()?"":" GROUP BY "+("trend".equals(group)?bucket(time,f):groupColumns(group))),w.args.toArray());
        for(int i=0;i<rows.length();i++)result.add(rows.getJSONObject(i));
    }
    private static String key(JSONObject r,String group){if(group.isEmpty())return "summary";if("trend".equals(group))return Long.toString(r.getLong("timestamp"));if("contact".equals(group))return r.getString("domain")+"\u0000"+r.getLong("contact_id");return r.getString("unit".equals(group)?"unit_id":group);}
    private static JSONArray merge(List<JSONObject> rows,String group){
        Map<String,JSONObject> totals=new TreeMap<>();
        for(JSONObject row:rows){String k=key(row,group);JSONObject sum=totals.get(k);if(sum==null){sum=new JSONObject(row.toString());for(String m:METRICS)sum.put(m,0L);totals.put(k,sum);}for(String m:METRICS)sum.put(m,Math.addExact(sum.getLong(m),row.optLong(m,0)));}
        JSONArray out=new JSONArray();for(JSONObject row:totals.values())out.put(row.put("total_tokens",Math.addExact(row.getLong("input_tokens"),row.getLong("output_tokens"))));return out;
    }
    public JSONObject query(String operation,Filter f){
        if(!Arrays.asList("summary","trend","breakdown","calls","bill","export").contains(operation))throw new IllegalArgumentException("Unknown usage query");
        if("bill".equals(operation)&&(f.contact==null||f.domain.isEmpty()||f.month.isEmpty()))throw new IllegalArgumentException("Bill requires domain, Contact and month");
        List<JSONObject> summary=new ArrayList<>(),trend=new ArrayList<>(),breakdown=new ArrayList<>(),calls=new ArrayList<>(),models=new ArrayList<>(),modules=new ArrayList<>();
        JSONArray status=new JSONArray();Set<String> identities=new HashSet<>();boolean complete=configError==null;long total=0;
        int selected=0,covered=0;
        for(int i=0;i<sources.length();i++){
            JSONObject s=sources.optJSONObject(i);String name=s==null?"source-"+i:s.optString("name","source-"+i);
            if(!f.source.isEmpty()&&!f.source.equals(name))continue;selected++;
            JSONObject state=new JSONObject().put("name",name);
            try{
                if(s==null)throw new SQLException("Invalid source");
                String prefix="harness".equals(s.getString("kind"))?"harness_ai_usage_":"aigc".equals(s.getString("kind"))?"aigc_ai_usage_":UsageSchema.prefix("");
                try(Connection c=connect(s)){
                    c.setAutoCommit(false);
                    JSONObject meta=new UsageLedger(()->{throw new SQLException("Reader only");},prefix,"mysql".equals(s.getString("type"))).metadata(c);
                    String identity=meta.getString("store_id");state.put("storeId",identity).put("collectedAt",meta.getLong("collected_at")).put("updatedAt",meta.getLong("updated_at"));
                    if(identities.contains(identity)){state.put("state","duplicate");status.put(state);continue;}
                    // Stage all results: a failure must not leave a half-counted source.
                    JSONArray ss=grouped(c,prefix,f,""),tt=grouped(c,prefix,f,"trend"),bb=grouped(c,prefix,f,f.group);
                    JSONArray mm="bill".equals(operation)?grouped(c,prefix,f,"model"):new JSONArray(),mo="bill".equals(operation)?grouped(c,prefix,f,"module"):new JSONArray();
                    Where w=new Where(f,"started_at",f.from,f.to);JSONArray cc=new JSONArray();
                    if(Arrays.asList("calls","bill","export").contains(operation)){
                        int take="export".equals(operation)?100001:f.offset+f.limit;
                        cc=UsageLedger.rows(c,"SELECT * FROM "+prefix+"call"+w.sql+" ORDER BY started_at DESC,meter_id DESC LIMIT "+take,w.args.toArray());
                    }
                    c.commit();identities.add(identity);
                    if(f.to>meta.getLong("collected_at"))covered++;
                    add(summary,ss);add(trend,tt);add(breakdown,bb);add(models,mm);add(modules,mo);
                    total+=ss.length()==0?0:ss.getJSONObject(0).optLong("calls");
                    for(int n=0;n<cc.length();n++)calls.add(cc.getJSONObject(n).put("source",name).put("storeId",identity));
                    boolean historyComplete=f.from>=meta.getLong("collected_at");
                    state.put("state","ready").put("historyComplete",historyComplete);
                    if(!historyComplete)complete=false;
                }
            }catch(Exception e){complete=false;state.put("state","unavailable");}
            status.put(state);
        }
        if(selected==0){complete=false;status.put(new JSONObject().put("name",f.source).put("state",configError==null?"not_configured":configError));}
        JSONObject sums=merge(summary,"").optJSONObject(0);if(sums==null){sums=new JSONObject();for(String m:METRICS)sums.put(m,0L);sums.put("total_tokens",0L);}
        JSONArray trends=merge(trend,"trend"),ranks=merge(breakdown,f.group);
        List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<trends.length();i++)ordered.add(trends.getJSONObject(i));ordered.sort(Comparator.comparingLong(o->o.getLong("timestamp")));trends=new JSONArray(ordered);
        calls.sort(Comparator.<JSONObject>comparingLong(o->o.getLong("started_at")).thenComparing(o->o.getString("storeId")).thenComparing(o->o.getString("meter_id")).reversed());
        boolean export="export".equals(operation);if(export&&total>100000)throw new IllegalArgumentException("Export exceeds 100000 calls; narrow the time range");
        int begin=export?0:Math.min(f.offset,calls.size()),end=export?calls.size():Math.min(begin+f.limit,calls.size());
        return new JSONObject().put("schemaVersion",1).put("timezone",ZONE.toString()).put("generatedAt",System.currentTimeMillis()).put("filter",f.json()).put("complete",complete)
                .put("sources",status).put("summary",identities.isEmpty()||covered==0?JSONObject.NULL:sums).put("trend",trends).put("breakdown",ranks).put("models",merge(models,"model")).put("modules",merge(modules,"module"))
                .put("calls",new JSONArray(calls.subList(begin,end))).put("total",total).put("offset",f.offset).put("limit",f.limit);
    }
    private static void add(List<JSONObject> target,JSONArray values){for(int i=0;i<values.length();i++)target.add(values.getJSONObject(i));}
    public static String csv(JSONObject data){
        StringBuilder out=new StringBuilder("\uFEFF");
        line(out,"generated_at","timezone","complete","domain","contact_id","from","to","model","module","unit","source","month","sources");JSONObject f=data.getJSONObject("filter");
        line(out,data.get("generatedAt"),data.get("timezone"),data.get("complete"),f.opt("domain"),f.opt("contact"),f.get("from"),f.get("to"),f.opt("model"),f.opt("module"),f.opt("unit"),f.opt("source"),f.opt("month"),data.getJSONArray("sources"));
        String[] columns={"domain","contact_id","model","module","unit_id","started_at","finished_at","status","quality","input_tokens","output_tokens","meter_id","request_id","run_id","source","revision"};
        line(out,(Object[])columns);JSONArray rows=data.getJSONArray("calls");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);Object[] v=new Object[columns.length];for(int j=0;j<columns.length;j++)v[j]=r.opt(columns[j]);line(out,v);}return out.toString();
    }
    private static void line(StringBuilder out,Object... values){for(int i=0;i<values.length;i++){if(i>0)out.append(',');String v=values[i]==null||values[i]==JSONObject.NULL?"":values[i].toString();if(v.matches("(?s)^[\\s\\uFEFF]*[=+@\\-].*")||v.startsWith("\t")||v.startsWith("\r"))v="'"+v;out.append('"').append(v.replace("\"","\"\"")).append('"');}out.append("\r\n");}
}
