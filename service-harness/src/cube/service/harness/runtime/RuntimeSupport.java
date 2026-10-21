/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * 新运行时的公共静态工具：封闭 schema、规范化内容与可移植路径。
 *
 * <p>本类集中三类不变量：id/digest 的词法规则、canonical JSON 的编解码入口、
 * 资产路径的字符级与真实路径级防护。路径防护分两层——{@link #path} 做字符级
 * 检查（拒绝穿越、绝对路径、DOS 设备名等），{@link #safeRoot}/{@link #resolve}
 * 在文件系统层拒绝符号链接与越界真实路径。
 *
 * <p>{@link #toolSchema} 用白名单词表 + 有界约束描述工具参数/结果 schema，
 * 拒绝开放对象（additionalProperties）、无界数组/字符串与过深嵌套。
 */
public final class RuntimeSupport {
    /**
     * 用错误码构造存储异常；调用方统一以 StoreException 向上传递语义错误。
     *
     * @param code 稳定错误码（如 FILE_BUSY、UNSAFE_ASSET_PATH）
     * @return 携带该错误码的 StoreException
     */
    public static StoreException error(String code) {
        return new StoreException(code);
    }

    /**
     * 校验并原样返回运行时标识（租户/主体/keyId 等）。
     *
     * @param s 待校验标识；允许 1-128 位字母、数字、下划线、点、冒号、连字符
     * @return 原标识
     * @throws cube.service.harness.store.StoreException 为 null 或不符合词法（INVALID_RUNTIME_ID）
     */
    public static String id(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw error("INVALID_RUNTIME_ID");
        }
        return s;
    }

    /**
     * 校验并原样返回内容寻址摘要。
     *
     * @param s 待校验摘要；必须是 64 位小写十六进制
     * @return 原摘要
     * @throws cube.service.harness.store.StoreException 为 null 或不符合词法（INVALID_DIGEST）
     */
    public static String digest(String s) {
        if (s == null || !s.matches("[a-f0-9]{64}")) {
            throw error("INVALID_DIGEST");
        }
        return s;
    }

    /**
     * 计算字节数组的小写十六进制 SHA-256。
     *
     * @param value 输入字节
     * @return 64 位小写十六进制摘要
     * @throws IllegalStateException JCA 提供 SHA-256 失败（不应发生）
     */
    public static String sha(byte[] value) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte v : MessageDigest.getInstance("SHA-256").digest(value)) {
                b.append(String.format("%02x", v & 255));
            }
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把对象序列化为规范化 JSON 的 UTF-8 字节。
     *
     * @param value 任意 JSON 兼容对象
     * @return 规范化（键排序、无空白）JSON 字节
     */
    public static byte[] bytes(Object value) {
        return JdbcRunStore.canonical(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 经规范化 JSON 深拷贝对象，隔离调用方与内部状态的共享引用。
     *
     * @param value 源 JSON 对象
     * @return 结构等价的新对象
     */
    public static JSONObject copy(JSONObject value) {
        return new JSONObject(JdbcRunStore.canonical(value));
    }

    /**
     * 把 JSON 数组转换为排序去重的字符串集合。
     *
     * @param values JSON 字符串数组
     * @return 排序后的不可变字符串集合（TreeSet）
     * @throws cube.service.harness.store.StoreException 数组含重复项（DUPLICATE_DECLARATION）
     */
    public static Set<String> strings(JSONArray values) {
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < values.length(); i++) {
            if (!out.add(values.getString(i))) {
                throw error("DUPLICATE_DECLARATION");
            }
        }
        return out;
    }

    /**
     * 有界读取输入流：累积超过上限立即失败，避免无界内存占用。
     *
     * @param in  输入流
     * @param max 允许读取的最大字节数
     * @return 已读的全部字节（长度不超过 {@code max}）
     * @throws IOException 流读取失败或超出上限（ASSET_TOO_LARGE）
     */
    public static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > max) {
                throw error("ASSET_TOO_LARGE");
            }
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 从类路径加载封闭 schema 资源（{@code /runtime/schemas/<name>.json}）。
     *
     * @param name schema 名（不含目录与后缀）
     * @return 解析后的 schema 对象；上限 256 KiB
     * @throws cube.service.harness.store.StoreException 资源缺失（RUNTIME_SCHEMA_MISSING）或读取失败
     */
    public static JSONObject schema(String name) {
        try (InputStream in = RuntimeSupport.class.getResourceAsStream("/runtime/schemas/" + name + ".json")) {
            if (in == null) {
                throw error("RUNTIME_SCHEMA_MISSING");
            }
            return StrictJson.object(read(in, 262144));
        } catch (IOException e) {
            throw new StoreException("RUNTIME_SCHEMA_MISSING", e);
        }
    }

    /**
     * 严格解析字节并按命名 schema 做契约校验。
     *
     * @param schema schema 资源名
     * @param raw    待解析字节（严格 JSON）
     * @return 校验通过的 JSON 对象
     */
    public static JSONObject parse(String schema, byte[] raw) {
        JSONObject value = StrictJson.object(raw);
        Contract.validate(value, schema(schema));
        return value;
    }

    /**
     * 按命名 schema 校验已有 JSON 对象。
     *
     * @param schema schema 资源名
     * @param value  待校验对象
     */
    public static void validate(String schema, JSONObject value) {
        Contract.validate(value, schema(schema));
    }

    /**
     * 校验并原样返回包内/资产相对路径（字符级防护）。
     *
     * <p>仅允许 1-256 位字母、数字、下划线、点、斜杠、连字符；拒绝首尾斜杠、
     * 空段（双斜杠）、"."、".."、以点结尾的段，以及 DOS 设备名
     * （CON/PRN/AUX/NUL/COM1-9/LPT1-9，取首段点号前缀大写比较）。
     *
     * @param value 相对路径
     * @return 原路径
     * @throws cube.service.harness.store.StoreException 为 null、含非法字符或段（UNSAFE_ASSET_PATH）
     */
    public static String path(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_./-]{1,256}") || value.startsWith("/") || value.endsWith("/")) {
            throw error("UNSAFE_ASSET_PATH");
        }
        for (String part : value.split("/", -1)) {
            String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw error("UNSAFE_ASSET_PATH");
            }
        }
        return value;
    }

    /**
     * 只接受实际校验器能理解的 schema 词表；递归校验参数/结果 schema。
     *
     * @param schema 待校验的工具 schema（顶层）
     * @throws cube.service.harness.store.StoreException 见 {@link #toolSchema(JSONObject, int)}
     */
    public static void toolSchema(JSONObject schema) {
        toolSchema(schema, 0);
    }

    /**
     * 工具 schema 的递归校验器：白名单词表 + 每类有界约束。
     *
     * <p>约束分类：
     * <ul>
     * <li>词表：只允许 type/properties/required/additionalProperties/enum/
     * minLength/maxLength/pattern/minimum/maximum/items/maxItems/minItems
     * （UNSUPPORTED_TOOL_SCHEMA）；</li>
     * <li>类型：只允许 object/array/string/integer/number/boolean
     * （UNSUPPORTED_TOOL_SCHEMA）；</li>
     * <li>object：additionalProperties 必须为 false（OPEN_TOOL_SCHEMA），
     * 属性名必须是合法 id，required 必须是 properties 的子集
     * （UNSUPPORTED_TOOL_SCHEMA），并递归校验每个属性；</li>
     * <li>array：必须声明 maxItems 且不超过 256（UNBOUNDED_TOOL_SCHEMA），
     * 并递归校验 items；</li>
     * <li>string：必须声明 maxLength 且不超过 65536（UNBOUNDED_TOOL_SCHEMA），
     * pattern 长度不超过 256（UNSUPPORTED_TOOL_SCHEMA）；</li>
     * <li>嵌套深度不超过 16 层（TOOL_SCHEMA_TOO_DEEP）。</li>
     * </ul>
     *
     * @param schema 当前层 schema
     * @param depth  当前嵌套深度（顶层为 0）
     * @throws cube.service.harness.store.StoreException 违反上述任一类约束时抛对应错误码
     */
    private static void toolSchema(JSONObject schema, int depth) {
        if (depth > 16) {
            throw error("TOOL_SCHEMA_TOO_DEEP");
        }
        Set<String> words = new HashSet<>(
                Arrays.asList("type", "properties", "required", "additionalProperties", "enum",
                        "minLength", "maxLength", "pattern", "minimum", "maximum", "items",
                        "maxItems", "minItems"));
        for (String key : schema.keySet()) {
            if (!words.contains(key)) {
                throw error("UNSUPPORTED_TOOL_SCHEMA");
            }
        }
        String type = schema.optString("type");
        if (!Arrays.asList("object", "array", "string", "integer", "number", "boolean").contains(type)) {
            throw error("UNSUPPORTED_TOOL_SCHEMA");
        }
        if (type.equals("object")) {
            // 开放对象会把未知字段透传给执行端，一律拒绝。
            if (schema.optBoolean("additionalProperties", true)) {
                throw error("OPEN_TOOL_SCHEMA");
            }
            JSONObject props = schema.getJSONObject("properties");
            for (String key : props.keySet()) {
                id(key);
                toolSchema(props.getJSONObject(key), depth + 1);
            }
            Set<String> required = strings(schema.getJSONArray("required"));
            if (!props.keySet().containsAll(required)) {
                throw error("UNSUPPORTED_TOOL_SCHEMA");
            }
        } else if (type.equals("array")) {
            // 无界数组会让模型侧生成不可控的调用体，必须显式设上限。
            if (!schema.has("maxItems") || schema.getInt("maxItems") > 256) {
                throw error("UNBOUNDED_TOOL_SCHEMA");
            }
            toolSchema(schema.getJSONObject("items"), depth + 1);
        } else if (type.equals("string")) {
            // 无界字符串同理；pattern 本身也要有界，避免复杂正则。
            if (!schema.has("maxLength") || schema.getInt("maxLength") > 65536) {
                throw error("UNBOUNDED_TOOL_SCHEMA");
            }
            if (schema.has("pattern") && schema.getString("pattern").length() > 256) {
                throw error("UNSUPPORTED_TOOL_SCHEMA");
            }
        }
    }

    /**
     * 校验资产根：绝对路径、已存在目录、自身不是符号链接，且真实路径自洽。
     *
     * @param root 候选根目录
     * @return 规范化后的真实根路径
     * @throws IOException                               底层文件系统查询失败
     * @throws cube.service.harness.store.StoreException 不是绝对目录或是符号链接（UNSAFE_ASSET_ROOT）
     */
    public static Path safeRoot(Path root) throws IOException {
        if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw error("UNSAFE_ASSET_ROOT");
        }
        if (!root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(root.toRealPath())) {
            throw error("UNSAFE_ASSET_ROOT");
        }
        return root.toRealPath();
    }

    /**
     * 把相对路径解析到根内，逐段做符号链接与越界真实路径检查。
     *
     * <p>先做字符级校验并 normalize，再要求结果以根前缀开头；随后从根开始逐段
     * 下行：已存在的段必须不是符号链接、NOFOLLOW 真实路径与跟随真实路径一致、
     * 且跟随真实路径仍在根内；不存在的段在 createParents 为真且非最终段时建目录。
     *
     * @param root          资产根（真实路径）
     * @param relative      根内相对路径
     * @param createParents 是否顺带创建缺失的中间目录
     * @return 解析后的规范路径（保证在根内）
     * @throws IOException                               目录创建等文件系统操作失败
     * @throws cube.service.harness.store.StoreException 路径非法或越界（UNSAFE_ASSET_PATH）
     */
    public static Path resolve(Path root, String relative, boolean createParents) throws IOException {
        Path result = root.resolve(path(relative)).normalize();
        if (!result.startsWith(root)) {
            throw error("UNSAFE_ASSET_PATH");
        }
        Path cursor = root;
        for (Path part : root.relativize(result)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !cursor.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(cursor.toRealPath())
                        || !cursor.toRealPath().startsWith(root)) {
                    throw error("UNSAFE_ASSET_PATH");
                }
            } else if (createParents && !cursor.equals(result)) {
                Files.createDirectory(cursor);
            }
        }
        return result;
    }
}
