package cube.service.harness.runtime;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Compacts text leaves while retaining valid JSON, array coverage and complete execution references. */
final class ContextData {
    private static final Set<String> EXACT = new HashSet<>(Arrays.asList(
            "release", "path", "name", "skill", "unit", "mediaType", "answer", "response", "sha256",
            "source", "location", "fileCode", "fileName"));
    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            "text", "content", "description", "snippet", "title", "message", "observation", "notes", "body", "excerpt"));

    static Object parse(String text) {
        try { return StrictJson.parse(text.getBytes(StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    static String compact(Object value, int allowance) {
        return JdbcRunStore.canonical(project(value, "", new int[]{allowance}));
    }

    private static Object project(Object value, String key, int[] remaining) {
        if (value instanceof JSONObject) {
            JSONObject out = new JSONObject(), object = (JSONObject)value;
            for (String child : new TreeSet<>(object.keySet())) out.put(child, project(object.get(child), child, remaining));
            // Resource cursors address the original codepoint stream, including the last page.
            if (object.has("release") && object.has("path") && object.has("offset") && object.has("nextOffset")
                    && object.opt("text") instanceof String && !object.getString("text").equals(out.getString("text"))) {
                if (out.getString("text").isEmpty() && !object.getString("text").isEmpty())
                    out.put("text", object.getString("text").substring(0, object.getString("text").offsetByCodePoints(0, 1)));
                out.put("nextOffset", object.getInt("offset") + out.getString("text").codePointCount(0, out.getString("text").length()));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray out = new JSONArray(), array = (JSONArray)value;
            for (int i=0;i<array.length();i++) out.put(project(array.get(i), key, remaining));
            return out;
        }
        if (!(value instanceof String)) return value;
        String text = (String)value;
        if (EXACT.contains(key) || key.matches("(?i).*(refs?|ids?|hash|digest|cursor|version|url|uri|path|token|code|key)$")) return text;
        if (key.endsWith("Json")) {
            Object nested = parse(text);
            if (nested instanceof JSONObject || nested instanceof JSONArray) return compactNested(nested, remaining);
        }
        // Unknown strings may be executable values. Only declared prose fields are shortened.
        if (!key.isEmpty() && !TEXT.contains(key)) return text;
        int length = text.codePointCount(0,text.length());
        // Small scalar values (counts, status, dates, enums) also remain exact.
        if (length <= 128) return text;
        int kept = Math.min(length, remaining[0]);
        remaining[0] -= kept;
        return text.substring(0,text.offsetByCodePoints(0,kept));
    }

    private static String compactNested(Object value, int[] remaining) {
        return JdbcRunStore.canonical(project(value,"",remaining));
    }
}
