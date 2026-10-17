package cube.service.harness.runtime;

import cube.service.harness.store.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The five resource tools operate only on the releases and permissions pinned to a run. */
public final class ResourceTools {
    private final CatalogStore catalog;
    private final PackageRepository repository;
    public ResourceTools(CatalogStore catalog,PackageRepository repository){this.catalog=catalog;this.repository=repository;}
    private JSONObject pin(ProfileRegistry.Identity identity,String run) { return catalog.pinned(identity.tenant,identity.subject,run); }
    private void permission(JSONObject pin,ProfileRegistry.Identity identity,String permission,JSONObject manifest) {
        Set<String> granted=RuntimeSupport.strings(pin.getJSONArray("permissions"));granted.retainAll(identity.permissions);
        if(manifest!=null)granted.retainAll(RuntimeSupport.strings(manifest.getJSONArray("permissions")));
        if(!granted.contains(permission))throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
    }
    private JSONObject manifest(ProfileRegistry.Identity identity,String run,String digest,String permission,boolean activated) {
        JSONObject pin=pin(identity,run),release=CatalogStore.requireRelease(pin,RuntimeSupport.digest(digest));JSONObject manifest=release.getJSONObject("manifest");
        permission(pin,identity,permission,manifest);
        if(activated){boolean active=false;JSONArray list=catalog.activeSkills(identity.tenant,identity.subject,run);for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("digest").equals(digest))active=true;
            if(!active)throw RuntimeSupport.error("SKILL_NOT_ACTIVE");}
        return manifest;
    }
    public JSONArray skills(ProfileRegistry.Identity identity,String run) {
        JSONObject pin=pin(identity,run);permission(pin,identity,"skill.list",null);JSONArray out=new JSONArray(),list=pin.getJSONArray("releases");
        for(int i=0;i<list.length();i++){
            JSONObject release=list.getJSONObject(i),manifest=release.getJSONObject("manifest");
            if(RuntimeSupport.strings(manifest.getJSONArray("permissions")).contains("skill.list"))out.put(new JSONObject().put("name",manifest.getString("name")).put("release",release.getString("digest")));
        }
        return out;
    }
    public void activate(ProfileRegistry.Identity identity,String run,String digest) { manifest(identity,run,digest,"skill.activate",false);catalog.activate(identity.tenant,identity.subject,run,digest); }
    public JSONArray resources(ProfileRegistry.Identity identity,String run,String digest) {
        JSONObject manifest=manifest(identity,run,digest,"resource.list",true);JSONArray out=new JSONArray(),list=manifest.getJSONArray("files");
        for(int i=0;i<list.length();i++){JSONObject file=list.getJSONObject(i);if(Arrays.asList("skill","resource").contains(file.getString("role")))out.put(RuntimeSupport.copy(file));}return out;
    }
    public JSONObject read(ProfileRegistry.Identity identity,String run,String digest,String path,int offset,int limit) throws Exception {
        if(offset<0 || limit<1 || limit>8192)throw RuntimeSupport.error("INVALID_RESOURCE_PAGE");
        JSONObject manifest=manifest(identity,run,digest,"resource.read",true),file=PackageRepository.file(manifest,path);
        if(!Arrays.asList("skill","resource").contains(file.getString("role")))throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
        String text=new String(repository.readVerified(manifest,path),StandardCharsets.UTF_8);int chars=text.codePointCount(0,text.length());
        if(offset>chars)throw RuntimeSupport.error("INVALID_RESOURCE_PAGE");int end=Math.min(chars,offset+limit);
        return new JSONObject().put("release",digest).put("path",path).put("sha256",file.getString("sha256"))
                .put("offset",offset).put("nextOffset",end==chars?-1:end).put("text",text.substring(text.offsetByCodePoints(0,offset),text.offsetByCodePoints(0,end)))
                .put("handle",new JSONObject().put("release",digest).put("path",path).put("sha256",file.getString("sha256")));
    }
    public JSONArray search(ProfileRegistry.Identity identity,String run,String digest,String needle,int limit) throws Exception {
        if(needle==null || needle.isEmpty() || needle.length()>256 || limit<1 || limit>100)throw RuntimeSupport.error("INVALID_RESOURCE_SEARCH");
        JSONObject manifest=manifest(identity,run,digest,"resource.search",true);JSONArray out=new JSONArray();
        for(int i=0;i<manifest.getJSONArray("files").length();i++) {
            JSONObject f=manifest.getJSONArray("files").getJSONObject(i);if(!Arrays.asList("skill","resource").contains(f.getString("role")))continue;
            String text=new String(repository.readVerified(manifest,f.getString("path")),StandardCharsets.UTF_8);int from=0,match;
            while((match=text.indexOf(needle,from))>=0 && out.length()<limit) {
                out.put(new JSONObject().put("path",f.getString("path")).put("offset",text.codePointCount(0,match)).put("sha256",f.getString("sha256"))
                        .put("preview",text.substring(match,Math.min(text.length(),match+128))));from=match+needle.length();
            }
            if(out.length()==limit)break;
        }return out;
    }
    public JSONArray tools(ProfileRegistry.Identity identity,String run) throws Exception {
        JSONObject pin=pin(identity,run);Set<String> permissions=RuntimeSupport.strings(pin.getJSONArray("permissions"));permissions.retainAll(identity.permissions);
        JSONArray out=new JSONArray();
        for(String name:ProfileRegistry.PLATFORM_PERMISSIONS)if(permissions.contains(name)){
            JSONObject properties=new JSONObject();JSONArray required=new JSONArray();
            if(!name.equals("skill.list")){properties.put("release",new JSONObject().put("type","string").put("pattern","^[a-f0-9]{64}$").put("maxLength",64));required.put("release");}
            if(name.equals("resource.read")){properties.put("path",new JSONObject().put("type","string").put("maxLength",256));properties.put("offset",new JSONObject().put("type","integer").put("minimum",0));properties.put("limit",new JSONObject().put("type","integer").put("minimum",1).put("maximum",8192));required.put("path").put("offset").put("limit");}
            if(name.equals("resource.search")){properties.put("query",new JSONObject().put("type","string").put("maxLength",256));properties.put("limit",new JSONObject().put("type","integer").put("minimum",1).put("maximum",100));required.put("query").put("limit");}
            out.put(new JSONObject().put("name",name).put("permission",name).put("description","Read pinned skill resources: "+name)
                    .put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",required).put("additionalProperties",false)));
        }
        Set<String> active=new HashSet<>();JSONArray activeRows=catalog.activeSkills(identity.tenant,identity.subject,run);
        for(int i=0;i<activeRows.length();i++)active.add(activeRows.getJSONObject(i).getString("digest"));
        for(int i=0;i<pin.getJSONArray("releases").length();i++){
            JSONObject release=pin.getJSONArray("releases").getJSONObject(i),manifest=release.getJSONObject("manifest");
            if(!active.contains(release.getString("digest")))continue;
            Set<String> allowed=new HashSet<>(permissions);allowed.retainAll(RuntimeSupport.strings(manifest.getJSONArray("permissions")));
            JSONArray declared=manifest.getJSONArray("tools");
            for(int j=0;j<declared.length();j++){
                JSONObject tool=declared.getJSONObject(j);if(!allowed.contains(tool.getString("permission")))continue;
                out.put(new JSONObject().put("name",tool.getString("name")).put("permission",tool.getString("permission"))
                        .put("description","Read immutable resource "+tool.getString("resourcePath"))
                        .put("resultSchema",cube.service.harness.protocol.StrictJson.object(repository.readVerified(manifest,tool.getString("outputSchema"))))
                        .put("parameters",cube.service.harness.protocol.StrictJson.object(repository.readVerified(manifest,tool.getString("inputSchema")))));
            }
        }
        return out;
    }
    /** Called by a trusted coordinator after admission; this method executes resource reads only. */
    public Object invoke(ProfileRegistry.Identity identity,String run,String name,JSONObject arguments) throws Exception {
        JSONArray available=tools(identity,run);JSONObject definition=null;
        for(int i=0;i<available.length();i++)if(available.getJSONObject(i).getString("name").equals(name))definition=available.getJSONObject(i);
        if(definition==null)throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
        cube.service.harness.protocol.Contract.validate(arguments,definition.getJSONObject("parameters"));
        if(name.equals("skill.list"))return skills(identity,run);
        String digest=arguments.optString("release");
        if(name.equals("skill.activate")){activate(identity,run,digest);return new JSONObject().put("activated",digest);}
        if(name.equals("resource.list"))return resources(identity,run,digest);
        if(name.equals("resource.read"))return read(identity,run,digest,arguments.getString("path"),arguments.getInt("offset"),arguments.getInt("limit"));
        if(name.equals("resource.search"))return search(identity,run,digest,arguments.getString("query"),arguments.getInt("limit"));
        JSONObject pin=pin(identity,run);JSONArray releases=pin.getJSONArray("releases");
        for(int i=0;i<releases.length();i++) {
            JSONObject release=releases.getJSONObject(i),manifest=release.getJSONObject("manifest");JSONArray declared=manifest.getJSONArray("tools");
            for(int j=0;j<declared.length();j++)if(declared.getJSONObject(j).getString("name").equals(name)) {
                JSONObject tool=declared.getJSONObject(j);
                JSONObject result=read(identity,run,release.getString("digest"),tool.getString("resourcePath"),arguments.optInt("offset",0),arguments.optInt("limit",1024));
                cube.service.harness.protocol.Contract.validate(result,definition.getJSONObject("resultSchema"));return result;
            }
        }
        throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
    }
}
