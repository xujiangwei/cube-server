package cube.aigc.unit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/** Canonical catalog encoding, shared by descriptors, execution intents and tests. */
public final class UnitJson {
    private UnitJson() { }

    public static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }

    public static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new IllegalArgumentException("UNIT_ID_INVALID");
        return value;
    }

    public static String canonical(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>(object.keySet());
            Collections.sort(keys);
            StringJoiner result = new StringJoiner(",", "{", "}");
            for (String key : keys) result.add(quote(key) + ":" + canonical(object.get(key)));
            return result.toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringJoiner result = new StringJoiner(",", "[", "]");
            for (int i = 0; i < array.length(); i++) result.add(canonical(array.get(i)));
            return result.toString();
        }
        if (value instanceof Number) return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        if (value instanceof Boolean) return value.toString();
        if (value instanceof String) return quote((String) value);
        throw new IllegalArgumentException("UNIT_JSON_INVALID");
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.append('"').toString();
    }

    public static String digest(Object value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
