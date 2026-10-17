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
 * Validates the original JSON before any JSONObject decoding can lose information.
 */
public final class StrictJson {
    public static final int MAX_BYTES = 262144, MAX_DEPTH = 32;
    private final String source;
    private int pos;

    private StrictJson(String source) {
        this.source = source;
    }

    public static Object parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("INVALID_JSON");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            StrictJson parser = new StrictJson(text);
            parser.value(0);
            parser.space();
            if (parser.pos != text.length()) parser.fail();
            return new org.json.JSONTokener(text).nextValue();
        } catch (RuntimeException | CharacterCodingException e) {
            throw new IllegalArgumentException("INVALID_JSON");
        }
    }

    public static JSONObject object(byte[] bytes) {
        Object value = parse(bytes);
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException("SCHEMA_INVALID");
        return (JSONObject) value;
    }

    private void fail() {
        throw new IllegalArgumentException("INVALID_JSON");
    }

    private void space() {
        while (pos < source.length() && " \r\n\t".indexOf(source.charAt(pos)) >= 0) pos++;
    }

    private char next() {
        if (pos >= source.length()) fail();
        return source.charAt(pos++);
    }

    private void expect(char c) {
        space();
        if (next() != c) fail();
    }

    private String string() {
        int start = pos;
        if (next() != '"') fail();
        while (true) {
            char c = next();
            if (c == '"') return new org.json.JSONTokener(source.substring(start, pos)).nextValue().toString();
            if (c < 32) fail();
            if (c == '\\') {
                char escape = next();
                if (escape == 'u') {
                    for (int i = 0; i < 4; i++) if ("0123456789abcdefABCDEF".indexOf(next()) < 0) fail();
                } else if ("\"\\/bfnrt".indexOf(escape) < 0) fail();
            }
        }
    }

    private void value(int depth) {
        if (depth > MAX_DEPTH) fail();
        space();
        if (pos >= source.length()) fail();
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
                if (!keys.add(key)) fail();
                expect(':');
                value(depth + 1);
                space();
                char end = next();
                if (end == '}') return;
                if (end != ',') fail();
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
                if (end == ']') return;
                if (end != ',') fail();
            }
        } else if (c == '"') string();
        else if (c == 't' || c == 'f' || c == 'n') {
            String literal = c == 't' ? "true" : c == 'f' ? "false" : "null";
            if (!source.startsWith(literal, pos)) fail();
            pos += literal.length();
        } else {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matcher(source);
            m.region(pos, source.length());
            if (!m.lookingAt()) fail();
            double number = Double.parseDouble(m.group());
            if (Double.isInfinite(number) || Double.isNaN(number)) fail();
            pos = m.end();
        }
    }
}
