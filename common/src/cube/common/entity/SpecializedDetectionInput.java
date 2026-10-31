/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.common.entity;

import cube.aigc.SpecializedDetectionException;
import cube.aigc.SpecializedDetectionProfile;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 对外的检测输入。仅有归属文件码与 RTSP 源允许跨越 API 边界。
 */
public final class SpecializedDetectionInput implements JSONable {
    /**
     * 单元名称。
     */
    public static final String UNIT = "SpecializedDetection";
    /**
     * 检测操作：detect、start、status 或 stop。
     */
    public final String operation;
    /**
     * 检测配置名称，控制类操作时为 null。
     */
    public final String profile;
    /**
     * 视频流标识，仅控制类操作有值。
     */
    public final String streamId;
    /**
     * 文件码或 RTSP 源地址，控制类操作时为 null。
     */
    public final String source;
    /**
     * 原始选项的副本。
     */
    private final JSONObject option;

    /**
     * 解析并校验检测输入。
     *
     * @param json 请求体中的 input 对象
     * @throws SpecializedDetectionException 当输入缺失、字段越界或参数非法时抛出
     */
    public SpecializedDetectionInput(JSONObject json) {
        try {
            if (json == null) {
                throw SpecializedDetectionException.invalid("input is required");
            }
            allowed(json, "unit", "files", "option");
            if (json.has("unit") && !UNIT.equals(json.get("unit"))) {
                throw SpecializedDetectionException.invalid("Unknown detection unit");
            }
            JSONObject value = json.has("option") ? json.getJSONObject("option") : new JSONObject();
            option = new JSONObject(value.toString());
            Object op = option.opt("operation");
            operation = op == null ? "detect" : op instanceof String ? (String) op : "";
            if (!Arrays.asList("detect", "start", "status", "stop").contains(operation)) {
                throw SpecializedDetectionException.invalid("Unknown detection operation");
            }
            JSONArray files = json.has("files") ? json.getJSONArray("files") : new JSONArray();
            if (isControl()) {
                allowed(option, "operation", "streamId");
                streamId = required(option, "streamId");
                if (files.length() != 0) {
                    throw SpecializedDetectionException.invalid("Controls require empty files");
                }
                profile = source = null;
            } else {
                if ("start".equals(operation)) {
                    allowed(option, "operation", "profile", "confidence", "sliced", "callbackUrl", "intervalSeconds", "inferenceFps");
                } else {
                    allowed(option, "operation", "profile", "confidence", "sliced");
                }
                profile = required(option, "profile");
                // Profile 是否可用由 Python 判定，包括比 Java 侧更新的 Profile。
                if (option.has("sliced") && (!(option.get("sliced") instanceof Boolean)
                        || (option.getBoolean("sliced") && !SpecializedDetectionProfile.UavFireSmoke.name.equals(profile)))) {
                    throw SpecializedDetectionException.invalid("sliced is supported only for uav-fire-smoke");
                }
                number(option, "confidence", 0, 1);
                number(option, "intervalSeconds", 0, 86400);
                number(option, "inferenceFps", 0.1, 60);
                if (files.length() != 1 || !(files.get(0) instanceof String)) {
                    throw SpecializedDetectionException.invalid("Exactly one file code or RTSP source is required");
                }
                source = files.getString(0);
                if (isRtsp()) {
                    URI uri = new URI(source);
                    if (!"start".equals(operation) || uri.getHost() == null || uri.getPort() > 65535
                            || !("rtsp".equalsIgnoreCase(uri.getScheme()) || "rtsps".equalsIgnoreCase(uri.getScheme()))) {
                        throw SpecializedDetectionException.invalid("RTSP requires start and a valid source URL");
                    }
                } else if (!source.matches("[A-Za-z0-9]{64}")) {
                    throw SpecializedDetectionException.invalid("Use an uploaded file code; local paths and HTTP media URLs are not accepted");
                }
                if ("start".equals(operation)) {
                    URI callback = new URI(required(option, "callbackUrl"));
                    if (callback.getHost() == null || callback.getPort() > 65535
                            || !("http".equalsIgnoreCase(callback.getScheme()) || "https".equalsIgnoreCase(callback.getScheme()))) {
                        throw SpecializedDetectionException.invalid("callbackUrl must be an HTTP(S) URL");
                    }
                }
                streamId = null;
            }
        } catch (SpecializedDetectionException error) {
            throw error;
        } catch (Exception error) {
            throw SpecializedDetectionException.invalid("Invalid detection input");
        }
    }

    /**
     * 读取必填的字符串字段。
     *
     * @param json 选项对象
     * @param key  字段名
     * @return 去除首尾空白前校验过的原始字符串
     * @throws SpecializedDetectionException 当字段缺失、非字符串或内容为空时抛出
     */
    private static String required(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw SpecializedDetectionException.invalid(key + " is required");
        }
        return (String) value;
    }

    /**
     * 校验对象中不存在白名单之外的字段。
     *
     * @param json  待校验对象
     * @param names 允许出现的字段名
     * @throws SpecializedDetectionException 当出现未支持的字段时抛出
     */
    private static void allowed(JSONObject json, String... names) {
        Set<String> keys = new HashSet<>(Arrays.asList(names));
        for (String name : json.keySet()) {
            if (!keys.contains(name)) {
                throw SpecializedDetectionException.invalid("Unsupported detection field: " + name);
            }
        }
    }

    /**
     * 校验数值字段是否落在闭区间内。字段不存在时直接返回。
     *
     * @param json 选项对象
     * @param key  字段名
     * @param low  下界，包含
     * @param high 上界，包含
     * @throws SpecializedDetectionException 当字段非数值、非有限值或越界时抛出
     */
    private static void number(JSONObject json, String key, double low, double high) {
        if (!json.has(key)) {
            return;
        }
        Object value = json.get(key);
        double n;
        try {
            n = value instanceof Boolean ? Double.NaN : Double.parseDouble(value.toString());
        } catch (Exception error) {
            n = Double.NaN;
        }
        if (Double.isNaN(n) || Double.isInfinite(n) || n < low || n > high) {
            throw SpecializedDetectionException.invalid("Invalid " + key);
        }
    }

    /**
     * 判断是否为控制类操作，即 status 与 stop。
     *
     * @return 控制类操作返回 true
     */
    public boolean isControl() {
        return "status".equals(operation) || "stop".equals(operation);
    }

    /**
     * 判断输入源是否为 RTSP 地址。
     *
     * @return 输入源以 rtsp:// 或 rtsps:// 开头时返回 true
     */
    public boolean isRtsp() {
        return source != null && (source.regionMatches(true, 0, "rtsp://", 0, 7) || source.regionMatches(true, 0, "rtsps://", 0, 8));
    }

    /**
     * 转换为发往 Unit 的请求 JSON。
     *
     * @param label 文件标签，为 null 时直接使用输入源字符串
     * @return Unit 请求 JSON
     */
    public JSONObject toUnitJSON(FileLabel label) {
        JSONArray files = new JSONArray();
        if (source != null) {
            files.put(label == null ? source : label.toJSON());
        }
        return new JSONObject().put("unit", UNIT).put("files", files).put("option", new JSONObject(option.toString()));
    }

    /**
     * 输出请求 JSON，不含文件标签。
     *
     * @return Unit 请求 JSON
     */
    @Override
    public JSONObject toJSON() {
        return toUnitJSON(null);
    }

    /**
     * 输出紧凑 JSON，与 {@link #toJSON()} 一致。
     *
     * @return Unit 请求 JSON
     */
    @Override
    public JSONObject toCompactJSON() {
        return toJSON();
    }
}
