/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 闭合的 Draft-07 契约词表校验器：schema 只允许 {@link #WORDS} 中的关键字，
 * 遇到不支持的关键字直接失败而不是静默忽略，保证两端契约语义一致。
 *
 * <p>校验覆盖 object/array/string/number/integer/boolean 六种类型及 oneOf
 * 互斥分支（必须恰好命中一个）。schema 文件作为 classpath 资源经
 * {@link #resource(String)} 读取，{@link #parse(String, byte[])} 串起
 * "先严格解析、后契约校验"的完整链路；所有校验失败统一以
 * {@code IllegalArgumentException("SCHEMA_INVALID")} 报告，由上层映射为协议错误码。
 */
public final class Contract {

    /**
     * 闭合词表：仅这些 Draft-07 关键字被接受，其余关键字一律拒绝而不是忽略。
     */
    private static final Set<String> WORDS =
            new HashSet<>(
                    Arrays.asList(
                            "$schema",
                            "oneOf",
                            "type",
                            "properties",
                            "required",
                            "additionalProperties",
                            "enum",
                            "minLength",
                            "maxLength",
                            "pattern",
                            "minimum",
                            "maximum",
                            "items",
                            "maxItems",
                            "minItems"));

    /**
     * 读取 classpath 下 {@code /contracts/} 目录中的契约资源文件。
     *
     * @param path 资源相对路径（不含 {@code /contracts/} 前缀），如 {@code schemas/xxx.json}
     * @return 资源文件的完整字节
     * @throws IOException 资源不存在（以 FileNotFoundException 携带 path）或读取失败
     */
    public static byte[] resource(String path) throws IOException {
        try (InputStream in = Contract.class.getResourceAsStream("/contracts/" + path)) {
            if (in == null) {
                throw new FileNotFoundException(path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 先对原始字节做严格 JSON 解析，再按指定 schema 文件执行契约校验。
     *
     * @param schema schema 文件名（不含目录与 {@code .json} 后缀），从 {@code /contracts/schemas/} 读取
     * @param bytes  待校验的原始 JSON 字节
     * @return 严格解析且通过契约校验的 JSON 对象
     * @throws IllegalArgumentException 输入不是合法 JSON，或不满足契约（错误码 INVALID_JSON/SCHEMA_INVALID）
     * @throws IllegalStateException    schema 资源缺失或不可读
     */
    public static JSONObject parse(String schema, byte[] bytes) {
        JSONObject value = StrictJson.object(bytes);
        try {
            validate(value, new JSONObject(new String(resource("schemas/" + schema + ".json"), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return value;
    }

    /**
     * 按闭合词表 schema 递归校验一个 JSON 值；schema 自身含未知关键字时直接失败。
     *
     * <p>oneOf 分支必须恰好命中一个：命中零个或多个都判失败；通过 oneOf 后若
     * 同层还声明了 type，则继续按 type 校验。类型、enum、required/
     * additionalProperties、items 数量、min/maxLength、pattern、
     * minimum/maximum 均按声明校验，未声明的约束取宽松默认（如
     * additionalProperties 默认允许）。
     *
     * @param value  待校验的 JSON 值（JSONObject/JSONArray/String/Number/Boolean）
     * @param schema Draft-07 子集 schema，只允许 {@link #WORDS} 中的关键字
     * @throws IllegalArgumentException 校验失败，错误码固定为 "SCHEMA_INVALID"
     * @throws IllegalStateException    schema 使用了闭合词表之外的关键字
     */
    public static void validate(Object value, JSONObject schema) {
        for (String key : schema.keySet()) {
            if (!WORDS.contains(key)) {
                throw new IllegalStateException("Unsupported schema keyword: " + key);
            }
        }
        if (schema.has("oneOf")) {
            JSONArray variants = schema.getJSONArray("oneOf");
            int matches = 0;
            for (int i = 0; i < variants.length(); i++) {
                try {
                    validate(value, variants.getJSONObject(i));
                    matches++;
                } catch (IllegalArgumentException ignored) {
                    // 分支未命中只影响计数，不视为整体失败；命中数最后由 matches != 1 判定。
                }
            }
            if (matches != 1) {
                bad();
            }
            if (!schema.has("type")) {
                return;
            }
        }
        String type = schema.optString("type");
        boolean good = type.equals("object") ? value instanceof JSONObject
                : type.equals("array") ? value instanceof JSONArray
                : type.equals("string") ? value instanceof String
                : type.equals("boolean") ? value instanceof Boolean
                : type.equals("number") ? value instanceof Number
                && Double.isFinite(((Number) value).doubleValue())
                : type.equals("integer") ? value instanceof Number
                && Double.isFinite(((Number) value).doubleValue())
                && ((Number) value).doubleValue()
                == Math.rint(((Number) value).doubleValue())
                : false;
        if (!good) {
            bad();
        }
        JSONArray choices = schema.optJSONArray("enum");
        if (choices != null) {
            boolean match = false;
            for (int i = 0; i < choices.length(); i++) {
                Object choice = choices.get(i);
                if (value instanceof Number && choice instanceof Number) {
                    if (((Number) value).doubleValue() == ((Number) choice).doubleValue()) {
                        match = true;
                    }
                } else if (choice.equals(value)) {
                    match = true;
                }
            }
            if (!match) {
                bad();
            }
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value, props = schema.optJSONObject("properties");
            JSONArray required = schema.optJSONArray("required");
            if (required != null) {
                for (int i = 0; i < required.length(); i++) {
                    if (!object.has(required.getString(i))) {
                        bad();
                    }
                }
            }
            for (String key : object.keySet()) {
                if (props == null || !props.has(key)) {
                    if (schema.opt("additionalProperties") instanceof JSONObject) {
                        validate(object.get(key), schema.getJSONObject("additionalProperties"));
                    } else if (!schema.optBoolean("additionalProperties", true)) {
                        bad();
                    }
                } else {
                    validate(object.get(key), props.getJSONObject(key));
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            if (a.length() < schema.optInt("minItems", 0) || a.length() > schema.optInt("maxItems", Integer.MAX_VALUE)) {
                bad();
            }
            for (int i = 0; i < a.length(); i++) {
                validate(a.get(i), schema.getJSONObject("items"));
            }
        } else if (value instanceof String) {
            String s = (String) value;
            int length = s.codePointCount(0, s.length());
            if (length < schema.optInt("minLength", 0) || length > schema.optInt("maxLength", Integer.MAX_VALUE)) {
                bad();
            }
            if (schema.has("pattern")
                    && !java.util.regex.Pattern.compile(schema.getString("pattern")).matcher(s).find()) {
                bad();
            }
        } else if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            if (n < schema.optDouble("minimum", -Double.MAX_VALUE) || n > schema.optDouble("maximum", Double.MAX_VALUE)) {
                bad();
            }
        }
    }

    /**
     * 统一失败出口：抛出的 IllegalArgumentException 固定携带 "SCHEMA_INVALID"。
     */
    private static void bad() {
        throw new IllegalArgumentException("SCHEMA_INVALID");
    }
}
