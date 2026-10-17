package cube.service.harness.protocol;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed Draft-07 contract vocabulary; unsupported keywords fail instead of being ignored. */
public final class Contract {
    private static final Set<String> WORDS = new HashSet<>(Arrays.asList("$schema","oneOf","type","properties","required","additionalProperties","enum","minLength","maxLength","pattern","minimum","maximum","items","maxItems","minItems"));
    public static byte[] resource(String path) throws IOException {
        try (InputStream in=Contract.class.getResourceAsStream("/contracts/"+path)) {
            if(in==null)throw new FileNotFoundException(path);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
        }
    }
    public static JSONObject parse(String schema, byte[] bytes) {
        JSONObject value=StrictJson.object(bytes);
        try { validate(value,new JSONObject(new String(resource("schemas/"+schema+".json"),StandardCharsets.UTF_8))); }
        catch(IOException e){throw new IllegalStateException(e);}
        return value;
    }
    public static void validate(Object value, JSONObject schema) {
        for(String key:schema.keySet())if(!WORDS.contains(key))throw new IllegalStateException("Unsupported schema keyword: "+key);
        if(schema.has("oneOf")) {
            JSONArray variants=schema.getJSONArray("oneOf");int matches=0;
            for(int i=0;i<variants.length();i++)try{validate(value,variants.getJSONObject(i));matches++;}catch(IllegalArgumentException ignored){}
            if(matches!=1)bad();
            if(!schema.has("type"))return;
        }
        String type=schema.optString("type");
        boolean good=type.equals("object")?value instanceof JSONObject:type.equals("array")?value instanceof JSONArray:
            type.equals("string")?value instanceof String:type.equals("boolean")?value instanceof Boolean:
            type.equals("integer")?value instanceof Number && ((Number)value).doubleValue()==Math.rint(((Number)value).doubleValue()):false;
        if(!good)bad();
        JSONArray choices=schema.optJSONArray("enum");
        if(choices!=null){boolean match=false;for(int i=0;i<choices.length();i++){
            Object choice=choices.get(i);
            if(value instanceof Number && choice instanceof Number){if(((Number)value).doubleValue()==((Number)choice).doubleValue())match=true;}
            else if(choice.equals(value))match=true;
        }if(!match)bad();}
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value,props=schema.optJSONObject("properties");JSONArray required=schema.optJSONArray("required");
            if(required!=null)for(int i=0;i<required.length();i++)if(!object.has(required.getString(i)))bad();
            for(String key:object.keySet()){
                if(props==null||!props.has(key)){if(!schema.optBoolean("additionalProperties",true))bad();}
                else validate(object.get(key),props.getJSONObject(key));
            }
        } else if(value instanceof JSONArray){
            JSONArray a=(JSONArray)value;if(a.length()<schema.optInt("minItems",0)||a.length()>schema.optInt("maxItems",Integer.MAX_VALUE))bad();
            for(int i=0;i<a.length();i++)validate(a.get(i),schema.getJSONObject("items"));
        } else if(value instanceof String){
            String s=(String)value;int length=s.codePointCount(0,s.length());
            if(length<schema.optInt("minLength",0)||length>schema.optInt("maxLength",Integer.MAX_VALUE))bad();
            if(schema.has("pattern")&&!java.util.regex.Pattern.compile(schema.getString("pattern")).matcher(s).find())bad();
        } else if(value instanceof Number){
            double n=((Number)value).doubleValue();if(n<schema.optDouble("minimum",-Double.MAX_VALUE)||n>schema.optDouble("maximum",Double.MAX_VALUE))bad();
        }
    }
    private static void bad(){throw new IllegalArgumentException("SCHEMA_INVALID");}
}
