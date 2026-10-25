package cube.service.harness.skill;

import cube.service.harness.store.StoreException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Budget the serialized JSON and the SDK's nested dataJson, including UTF-8 and escaping. */
final class Budget {
    private Budget() {}
    static int runnerBytes(JSONObject data) {
        JSONObject value = new JSONObject().put("status", "success").put("dataJson", data.toString())
                .put("artifacts", new JSONArray());
        return new JSONObject().put("type", "result").put("value", value).toString()
                .getBytes(StandardCharsets.UTF_8).length + 1;
    }
    static String prefix(String value, int chars) {
        int end = Math.min(value.length(), Math.max(0, chars));
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
    static void text(JSONObject data, String field, int maxBytes) {
        String original = data.getString(field);
        if (AbstractProvider.bytes(data) <= maxBytes) return;
        data.put("truncated", true);
        int low = 0, high = original.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            data.put(field, prefix(original, mid));
            if (AbstractProvider.bytes(data) <= maxBytes) low = mid;
            else high = mid - 1;
        }
        data.put(field, prefix(original, low));
        if (AbstractProvider.bytes(data) > maxBytes) throw new StoreException("TOOL_RESULT_LIMIT");
    }
    static void items(JSONObject data, String field, int maxBytes) {
        JSONArray items = data.getJSONArray(field);
        while (AbstractProvider.bytes(data) > maxBytes && items.length() > 0) {
            data.put("truncated", true);
            items.remove(items.length() - 1);
        }
        if (AbstractProvider.bytes(data) > maxBytes) throw new StoreException("TOOL_RESULT_LIMIT");
    }
}
