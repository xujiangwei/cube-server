package cube.service.harness.runtime;

import cube.service.harness.store.JdbcRunStore;
import org.json.*;

/** Complete, bounded selection from original decoded tool data (RFC 6901). */
final class ContextReadback {
    static JSONObject select(JSONObject result, String pointer, int offset, int limit, String intent, String hash) {
        if (offset != 0 || pointer.length() > 1024 || !pointer.isEmpty() && !pointer.startsWith("/"))
            throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_INVALID");
        Object value = ContextData.parse(result.optString("dataJson"));
        if (value == null) throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_INVALID");
        String[] parts = pointer.isEmpty() ? new String[0] : pointer.substring(1).split("/", -1);
        if (parts.length > 32) throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_INVALID");
        for (String part : parts) {
            if (java.util.regex.Pattern.compile("~(?![01])").matcher(part).find()) throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_INVALID");
            String key = part.replace("~1", "/").replace("~0", "~");
            if (value instanceof JSONObject && ((JSONObject)value).has(key)) value = ((JSONObject)value).get(key);
            else if (value instanceof JSONArray && key.matches("0|[1-9][0-9]{0,8}")) {
                int index = Integer.parseInt(key);
                if (index >= ((JSONArray)value).length()) throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_NOT_FOUND");
                value = ((JSONArray)value).get(index);
            } else throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_NOT_FOUND");
        }
        String raw = JdbcRunStore.canonical(value);
        if (raw.codePointCount(0, raw.length()) > limit) throw RuntimeSupport.error("CONTEXT_RESULT_SELECTION_TOO_LARGE");
        return new JSONObject().put("encoding", "tool-result-selection-json-v1").put("intent", intent).put("resultHash", hash)
                .put("selector", pointer).put("selectedHash", JdbcRunStore.hash(value)).put("complete", true).put("value", value);
    }
}
