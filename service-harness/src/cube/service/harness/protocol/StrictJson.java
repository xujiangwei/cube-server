/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.protocol;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 在交给 org.json 解码之前先校验原始字节：只接受严格 JSON，拒绝一切
 * org.json 会静默容忍或解码后丢失信息的宽松输入。
 *
 * <p>约束包括：输入不超过 {@link #MAX_BYTES} 字节、UTF-8 严格解码、嵌套不超过
 * {@link #MAX_DEPTH} 层、对象键唯一、字符串内控制字符与非法转义拒绝、数字必须
 * 是有限值且无前导零、解析结束后不得有尾随内容。任一约束不满足都以
 * {@code IllegalArgumentException("INVALID_JSON")} 失败。
 */
public final class StrictJson {

    /**
     * 原始输入的最大字节数（262144，即 256 KiB）；超出直接拒绝，不做部分读取。
     */
    public static final int MAX_BYTES = 262144;

    /**
     * 允许的最大嵌套深度（32）；每进入一层 object/array 计数加一。
     */
    public static final int MAX_DEPTH = 32;

    /**
     * 待校验文本：原始字节经严格 UTF-8 解码得到；校验期间只读。
     */
    private final String source;

    /**
     * 当前扫描下标；由 space/next 向前推进，失败即抛错、不回退。
     */
    private int pos;

    /**
     * 私有构造：只允许静态入口创建，保证校验始终从原始字节开始。
     *
     * @param source 严格 UTF-8 解码后的 JSON 文本
     */
    private StrictJson(String source) {
        this.source = source;
    }

    /**
     * 严格校验原始字节并返回解码后的 JSON 值；校验与解码针对同一份文本，
     * 避免 org.json 先行解码丢失信息。
     *
     * @param bytes 原始 JSON 字节（UTF-8）
     * @return 解码后的 JSONObject / JSONArray / String / Number / Boolean / null
     * @throws IllegalArgumentException 超过 MAX_BYTES、UTF-8 非法、词法/结构/转义/深度/
     *                                  重复键/数字格式不符或存在尾随内容；错误码统一为 "INVALID_JSON"
     */
    public static Object parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("INVALID_JSON");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            StrictJson parser = new StrictJson(text);
            parser.value(0);
            parser.space();
            if (parser.pos != text.length()) {
                parser.fail();
            }
            return new org.json.JSONTokener(text).nextValue();
        } catch (RuntimeException | CharacterCodingException e) {
            throw new IllegalArgumentException("INVALID_JSON");
        }
    }

    /**
     * 严格解析字节并强制要求顶层是 JSON 对象。
     *
     * @param bytes 原始 JSON 字节（UTF-8）
     * @return 顶层 JSON 对象
     * @throws IllegalArgumentException 输入非法（"INVALID_JSON"）或顶层不是对象（"SCHEMA_INVALID"）
     */
    public static JSONObject object(byte[] bytes) {
        Object value = parse(bytes);
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException("SCHEMA_INVALID");
        }
        return (JSONObject) value;
    }

    /**
     * 统一失败出口：抛 "INVALID_JSON"。
     */
    private void fail() {
        throw new IllegalArgumentException("INVALID_JSON");
    }

    /**
     * 跳过 JSON 空白字符（空格、回车、换行、制表符）；其他字符原地停留。
     */
    private void space() {
        while (pos < source.length() && " \r\n\t".indexOf(source.charAt(pos)) >= 0) {
            pos++;
        }
    }

    /**
     * 取下一个字符并前移；到末尾说明输入被截断。
     *
     * @return source 中当前位置的字符
     * @throws IllegalArgumentException 已到输入末尾（JSON 被截断）
     */
    private char next() {
        if (pos >= source.length()) {
            fail();
        }
        return source.charAt(pos++);
    }

    /**
     * 跳过空白后，要求下一个字符等于指定字面量。
     *
     * @param c 期望字符（如 ':'）
     * @throws IllegalArgumentException 实际字符不匹配
     */
    private void expect(char c) {
        space();
        if (next() != c) {
            fail();
        }
    }

    /**
     * 解析 JSON 字符串字面量并返回解码值；仅允许 JSON 规定的转义序列，
     * 32 以下的控制字符直接拒绝。
     *
     * @return 解码后的字符串内容
     * @throws IllegalArgumentException 缺少引号、含控制字符、转义序列不被支持或转义的十六进制不足四位
     */
    private String string() {
        int start = pos;
        if (next() != '"') {
            fail();
        }
        while (true) {
            char c = next();
            if (c == '"') {
                return new org.json.JSONTokener(source.substring(start, pos)).nextValue().toString();
            }
            if (c < 32) {
                fail();
            }
            if (c == '\\') {
                char escape = next();
                if (escape == 'u') {
                    for (int i = 0; i < 4; i++) {
                        if ("0123456789abcdefABCDEF".indexOf(next()) < 0) {
                            fail();
                        }
                    }
                } else if ("\"\\/bfnrt".indexOf(escape) < 0) {
                    fail();
                }
            }
        }
    }

    /**
     * 递归解析一个 JSON 值：object/array/string/true/false/null/数字。
     * 同一对象内键必须唯一；数组元素逐项递归。
     *
     * @param depth 当前嵌套深度，进入 object/array 时加一
     * @throws IllegalArgumentException 超过 MAX_DEPTH，或任一子结构不合法
     */
    private void value(int depth) {
        if (depth > MAX_DEPTH) {
            fail();
        }
        space();
        if (pos >= source.length()) {
            fail();
        }
        char c = source.charAt(pos);
        if (c == '{') {
            pos++;
            space();
            Set<String> keys = new HashSet<>();
            if (pos < source.length() && source.charAt(pos) == '}') {
                pos++;
                return;
            }
            while (true) {
                space();
                String key = string();
                if (!keys.add(key)) {
                    fail();
                }
                expect(':');
                value(depth + 1);
                space();
                char end = next();
                if (end == '}') {
                    return;
                }
                if (end != ',') {
                    fail();
                }
            }
        } else if (c == '[') {
            pos++;
            space();
            if (pos < source.length() && source.charAt(pos) == ']') {
                pos++;
                return;
            }
            while (true) {
                value(depth + 1);
                space();
                char end = next();
                if (end == ']') {
                    return;
                }
                if (end != ',') {
                    fail();
                }
            }
        } else if (c == '"') {
            string();
        } else if (c == 't' || c == 'f' || c == 'n') {
            String literal = c == 't' ? "true" : c == 'f' ? "false" : "null";
            if (!source.startsWith(literal, pos)) {
                fail();
            }
            pos += literal.length();
        } else {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
                            .matcher(source);
            m.region(pos, source.length());
            if (!m.lookingAt()) {
                fail();
            }
            double number = Double.parseDouble(m.group());
            if (Double.isInfinite(number) || Double.isNaN(number)) {
                fail();
            }
            pos = m.end();
        }
    }
}
