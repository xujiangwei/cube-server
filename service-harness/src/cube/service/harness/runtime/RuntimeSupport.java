package cube.service.harness.runtime;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Closed schemas, canonical content and portable paths for the new runtime only. */
public final class RuntimeSupport {
    public static StoreException error(String code) { return new StoreException(code); }
    public static String id(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_.:-]{1,128}")) throw error("INVALID_RUNTIME_ID"); return s;
    }
    public static String digest(String s) {
        if (s == null || !s.matches("[a-f0-9]{64}")) throw error("INVALID_DIGEST"); return s;
    }
    public static String sha(byte[] value) {
        try { StringBuilder b = new StringBuilder(); for (byte v : MessageDigest.getInstance("SHA-256").digest(value)) b.append(String.format("%02x",v&255)); return b.toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static byte[] bytes(Object value) { return JdbcRunStore.canonical(value).getBytes(StandardCharsets.UTF_8); }
    public static JSONObject copy(JSONObject value) { return new JSONObject(JdbcRunStore.canonical(value)); }
    public static Set<String> strings(JSONArray values) {
        Set<String> out = new TreeSet<>();
        for (int i=0;i<values.length();i++) if (!out.add(values.getString(i))) throw error("DUPLICATE_DECLARATION");
        return out;
    }
    public static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n=in.read(b))!=-1) { if (out.size()+n>max) throw error("ASSET_TOO_LARGE"); out.write(b,0,n); }
        return out.toByteArray();
    }
    public static JSONObject schema(String name) {
        try (InputStream in = RuntimeSupport.class.getResourceAsStream("/runtime/schemas/"+name+".json")) {
            if (in==null) throw error("RUNTIME_SCHEMA_MISSING"); return StrictJson.object(read(in,262144));
        } catch (IOException e) { throw new StoreException("RUNTIME_SCHEMA_MISSING",e); }
    }
    public static JSONObject parse(String schema, byte[] raw) {
        JSONObject value=StrictJson.object(raw); Contract.validate(value,schema(schema)); return value;
    }
    public static void validate(String schema, JSONObject value) { Contract.validate(value,schema(schema)); }
    public static String path(String value) {
        if (value==null || !value.matches("[A-Za-z0-9_./-]{1,256}") || value.startsWith("/") || value.endsWith("/")) throw error("UNSAFE_ASSET_PATH");
        for (String part:value.split("/",-1)) {
            String stem=part.split("\\.",2)[0].toUpperCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw error("UNSAFE_ASSET_PATH");
        }
        return value;
    }
    /** Only the schema vocabulary understood by the actual validator is accepted. */
    public static void toolSchema(JSONObject schema) { toolSchema(schema,0); }
    private static void toolSchema(JSONObject schema,int depth) {
        if (depth>16) throw error("TOOL_SCHEMA_TOO_DEEP");
        Set<String> words=new HashSet<>(Arrays.asList("type","properties","required","additionalProperties","enum","minLength","maxLength","pattern","minimum","maximum","items","maxItems","minItems"));
        for (String key:schema.keySet()) if (!words.contains(key)) throw error("UNSUPPORTED_TOOL_SCHEMA");
        String type=schema.optString("type");
        if (!Arrays.asList("object","array","string","integer","boolean").contains(type)) throw error("UNSUPPORTED_TOOL_SCHEMA");
        if (type.equals("object")) {
            if (schema.optBoolean("additionalProperties",true)) throw error("OPEN_TOOL_SCHEMA");
            JSONObject props=schema.getJSONObject("properties");
            for (String key:props.keySet()) { id(key); toolSchema(props.getJSONObject(key),depth+1); }
            Set<String> required=strings(schema.getJSONArray("required"));
            if (!props.keySet().containsAll(required)) throw error("UNSUPPORTED_TOOL_SCHEMA");
        } else if (type.equals("array")) {
            if (!schema.has("maxItems") || schema.getInt("maxItems")>256) throw error("UNBOUNDED_TOOL_SCHEMA");
            toolSchema(schema.getJSONObject("items"),depth+1);
        } else if (type.equals("string")) {
            if (!schema.has("maxLength") || schema.getInt("maxLength")>65536) throw error("UNBOUNDED_TOOL_SCHEMA");
            if (schema.has("pattern") && schema.getString("pattern").length()>256) throw error("UNSUPPORTED_TOOL_SCHEMA");
        }
    }
    public static Path safeRoot(Path root) throws IOException {
        if (!root.isAbsolute() || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw error("UNSAFE_ASSET_ROOT");
        return root.toRealPath();
    }
    public static Path resolve(Path root,String relative,boolean createParents) throws IOException {
        Path result=root.resolve(path(relative)).normalize();
        if (!result.startsWith(root)) throw error("UNSAFE_ASSET_PATH");
        Path cursor=root;
        for (Path part:root.relativize(result)) {
            cursor=cursor.resolve(part);
            if (Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().startsWith(root)) throw error("UNSAFE_ASSET_PATH");
            } else if (createParents && !cursor.equals(result)) Files.createDirectory(cursor);
        }
        return result;
    }
}
