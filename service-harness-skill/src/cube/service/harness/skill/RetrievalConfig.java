package cube.service.harness.skill;

import cube.service.harness.store.StoreException;
import org.json.*;
import java.time.ZoneId;
import java.util.*;

/** Immutable business retrieval routes. Configuration never installs executable code. */
final class RetrievalConfig {
    static final String FILE = "retrieval-profiles.json";

    static void require(boolean value) { if (!value) throw new StoreException("RETRIEVAL_CONFIG_INVALID"); }
    static String text(JSONObject o, String key, int max) {
        Object value = o.get(key);
        require(value instanceof String && !((String)value).trim().isEmpty() && ((String)value).length() <= max);
        return (String)value;
    }
    static String id(JSONObject o, String key) {
        String id = text(o, key, 128); require(id.matches("[A-Za-z0-9_.:-]+")); return id;
    }
    static int integer(JSONObject o, String key, int min, int max) {
        require(o.get(key) instanceof Number && o.getDouble(key) == o.getInt(key));
        int n = o.getInt(key); require(n >= min && n <= max); return n;
    }
    static void strings(JSONArray a, int maxItems, int maxChars, boolean empty) {
        require(a.length() <= maxItems && (empty || a.length() > 0));
        Set<String> seen = new HashSet<>();
        for (Object v : a) require(v instanceof String && !((String)v).trim().isEmpty()
                && ((String)v).length() <= maxChars && seen.add(normal((String)v)));
    }
    static String normal(String value) { return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim(); }
    static void pointers(JSONObject fields, String required, String optional) {
        ConnectorConfig.keys(fields, required, optional);
        for (String k : fields.keySet()) {
            String p = text(fields, k, 256); require(p.startsWith("/"));
        }
    }
    static void source(JSONObject o) { require(text(o,"sourceRef",260).matches("[A-Za-z0-9_.:-]{1,128}@[A-Za-z0-9_.:-]{1,128}")); }

    static void validate(JSONObject config) {
        try {
            ConnectorConfig.keys(config, "version,zone,regions,profiles", "");
            require("1".equals(config.getString("version"))); ZoneId.of(config.getString("zone"));
            JSONObject regions = config.getJSONObject("regions");
            String kind = regions.getString("kind");
            if (kind.equals("inline")) {
                ConnectorConfig.keys(regions,"kind,items", ""); catalog(regions.getJSONArray("items"));
            } else {
                require(kind.equals("http")); ConnectorConfig.keys(regions,"kind,sourceRef,request,response", ""); source(regions);
                ConnectorConfig.request(regions.getJSONObject("request"),"args");
                for (Object v : regions.getJSONObject("request").getJSONObject("query").toMap().values()) require(!v.toString().contains("${"));
                JSONObject response=regions.getJSONObject("response");
                ConnectorConfig.keys(response,"items,complete,fields", "");
                text(response,"items",256); text(response,"complete",256);
                pointers(response.getJSONObject("fields"),"id,name,aliases,parentId", "");
            }
            JSONArray profiles = config.getJSONArray("profiles"); require(profiles.length()>0 && profiles.length()<=8);
            Set<String> ids = new HashSet<>();
            for (Object entry:profiles) {
                JSONObject p=(JSONObject)entry;
                ConnectorConfig.keys(p,"id,label,resourceType,aliases,sourceRef,request,response,pagination,time,regionRequired,regionScope,mediaTypes,pageSize,maxPages", "vocabulary,filters");
                require(ids.add(id(p,"id"))); text(p,"label",128); id(p,"resourceType"); strings(p.getJSONArray("aliases"),16,64,false); source(p);
                require(p.get("regionRequired") instanceof Boolean); require(Arrays.asList("exact","subtree").contains(p.getString("regionScope")));
                strings(p.getJSONArray("mediaTypes"),16,128,false);
                for (Object type:p.getJSONArray("mediaTypes")) require(type.toString().matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"));
                integer(p,"pageSize",1,8); integer(p,"maxPages",1,64);
                Set<String> arguments=new HashSet<>(Arrays.asList("from","to","regionId","regionScope","query","pageSize","resourceType","intent"));
                String filterFields="";
                if(p.has("vocabulary")) strings(p.getJSONArray("vocabulary"),32,64,true);
                JSONArray filters=p.optJSONArray("filters");
                if(filters!=null) {
                    require(filters.length()<=4); Set<String> filterIds=new HashSet<>();
                    for(Object f:filters) {
                        JSONObject filter=(JSONObject)f; ConnectorConfig.keys(filter,"id,label,required,values", "");
                        String fid=id(filter,"id"); require(fid.matches("[A-Za-z][A-Za-z0-9_]{0,31}") && filterIds.add(fid)); arguments.add("filter_"+fid); filterFields+=",filter_"+fid;
                        text(filter,"label",64); require(filter.get("required") instanceof Boolean);
                        JSONArray values=filter.getJSONArray("values"); require(values.length()>0 && values.length()<=16); Set<String> valuesSeen=new HashSet<>();
                        for(Object v:values) { JSONObject value=(JSONObject)v; ConnectorConfig.keys(value,"id,label,aliases", ""); require(valuesSeen.add(id(value,"id")));text(value,"label",64);strings(value.getJSONArray("aliases"),8,64,false); }
                    }
                }
                JSONObject request=p.getJSONObject("request"); ConnectorConfig.request(request,"args");
                for(Object v:request.getJSONObject("query").toMap().values()) {
                    String value=v.toString(); if(value.startsWith("${args.")) require(arguments.contains(value.substring(7,value.length()-1)));
                }
                JSONObject time=p.getJSONObject("time"); ConnectorConfig.keys(time,"mode,required,allowFuture,maxRangeHours", "");
                String mode=time.getString("mode"); require(Arrays.asList("point","overlap","none").contains(mode));
                require(time.get("required") instanceof Boolean && time.get("allowFuture") instanceof Boolean);
                require(!mode.equals("none") || !time.getBoolean("required")); integer(time,"maxRangeHours",1,8784);
                JSONObject response=p.getJSONObject("response");ConnectorConfig.keys(response,"items,fields", "");text(response,"items",256);
                JSONObject fields=response.getJSONObject("fields");pointers(fields,"id,version,title,mediaType"+filterFields, "regionId,start,end,summary");
                if(p.getBoolean("regionRequired"))require(fields.has("regionId"));
                if(!mode.equals("none"))require(fields.has("start")); if(mode.equals("overlap"))require(fields.has("end"));
                JSONObject pg=p.getJSONObject("pagination"); ConnectorConfig.keys(pg,"nextCursor,snapshotId,cursorParameter,snapshotParameter", "");
                text(pg,"nextCursor",256);text(pg,"snapshotId",256);
                for(String key:Arrays.asList("cursorParameter","snapshotParameter")) {
                    require(text(pg,key,64).matches("[A-Za-z][A-Za-z0-9_]{0,63}"));require(!request.getJSONObject("query").has(pg.getString(key)));
                }
                require(!pg.getString("cursorParameter").equals(pg.getString("snapshotParameter")));
                Set<String> mapped=new HashSet<>();for(Object v:request.getJSONObject("query").toMap().values())mapped.add(v.toString());
                if(!mode.equals("none"))require(mapped.contains("${args.from}")&&mapped.contains("${args.to}"));
                if(p.getBoolean("regionRequired"))require(mapped.contains("${args.regionId}"));
                if(mapped.contains("${args.regionId}")) {
                    require(fields.has("regionId"));
                    if(p.getString("regionScope").equals("subtree"))require(mapped.contains("${args.regionScope}"));
                }
                if(filters!=null)for(Object f:filters)require(mapped.contains("${args.filter_"+((JSONObject)f).getString("id")+"}"));
            }
        } catch(Exception error) { throw new StoreException("RETRIEVAL_CONFIG_INVALID"); }
    }

    static JSONArray catalog(JSONArray items) {
        require(items.length()<=128); Map<String,JSONObject> ids=new HashMap<>();
        for(Object entry:items) {
            JSONObject r=(JSONObject)entry;ConnectorConfig.keys(r,"id,name,aliases,parentId", "");
            require(ids.put(id(r,"id"),r)==null);text(r,"name",128);strings(r.getJSONArray("aliases"),16,128,true);
            require(r.get("parentId") instanceof String && r.getString("parentId").length()<=128);
        }
        for(JSONObject r:ids.values()) {
            Set<String> seen=new HashSet<>();String parent=r.getString("id");
            while(!parent.isEmpty()) { require(seen.add(parent)&&ids.containsKey(parent));parent=ids.get(parent).getString("parentId"); }
        }
        return items;
    }
    static JSONObject profile(JSONObject config,String id) {
        for(Object entry:config.getJSONArray("profiles"))if(((JSONObject)entry).getString("id").equals(id))return (JSONObject)entry;
        throw new StoreException("RETRIEVAL_PROFILE_INVALID");
    }
    static Set<String> descendants(JSONArray regions,String selected,boolean subtree) {
        Set<String> result=new TreeSet<>();if(selected.isEmpty())return result; result.add(selected);
        boolean changed;do { changed=false;for(Object entry:regions) {JSONObject r=(JSONObject)entry;if(subtree&&result.contains(r.getString("parentId")))changed|=result.add(r.getString("id"));}}while(changed);
        return result;
    }
}
