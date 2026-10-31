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
 * External detection input. Only owned file codes and RTSP sources cross the API boundary.
 */
public final class SpecializedDetectionInput implements JSONable {
    public static final String UNIT = "SpecializedDetection";
    public final String operation;
    public final String profile;
    public final String streamId;
    public final String source;
    private final JSONObject option;

    public SpecializedDetectionInput(JSONObject json) {
        try {
            if (json == null) throw SpecializedDetectionException.invalid("input is required");
            allowed(json, "unit", "files", "option");
            if (json.has("unit") && !UNIT.equals(json.get("unit")))
                throw SpecializedDetectionException.invalid("Unknown detection unit");
            JSONObject value = json.has("option") ? json.getJSONObject("option") : new JSONObject();
            option = new JSONObject(value.toString());
            Object op = option.opt("operation");
            operation = op == null ? "detect" : op instanceof String ? (String) op : "";
            if (!Arrays.asList("detect", "start", "status", "stop").contains(operation))
                throw SpecializedDetectionException.invalid("Unknown detection operation");
            JSONArray files = json.has("files") ? json.getJSONArray("files") : new JSONArray();
            if (isControl()) {
                allowed(option, "operation", "streamId");
                streamId = required(option, "streamId");
                if (files.length() != 0) throw SpecializedDetectionException.invalid("Controls require empty files");
                profile = source = null;
            } else {
                if ("start".equals(operation))
                    allowed(option, "operation", "profile", "confidence", "sliced", "callbackUrl", "intervalSeconds", "inferenceFps");
                else allowed(option, "operation", "profile", "confidence", "sliced");
                profile = required(option, "profile");
                // Profile availability is decided by Python, including profiles newer than Java.
                if (option.has("sliced") && (!(option.get("sliced") instanceof Boolean)
                        || (option.getBoolean("sliced") && !SpecializedDetectionProfile.UavFireSmoke.name.equals(profile))))
                    throw SpecializedDetectionException.invalid("sliced is supported only for uav-fire-smoke");
                number(option, "confidence", 0, 1);
                number(option, "intervalSeconds", 0, 86400);
                number(option, "inferenceFps", 0.1, 60);
                if (files.length() != 1 || !(files.get(0) instanceof String))
                    throw SpecializedDetectionException.invalid("Exactly one file code or RTSP source is required");
                source = files.getString(0);
                if (isRtsp()) {
                    URI uri = new URI(source);
                    if (!"start".equals(operation) || uri.getHost() == null || uri.getPort() > 65535
                            || !("rtsp".equalsIgnoreCase(uri.getScheme()) || "rtsps".equalsIgnoreCase(uri.getScheme())))
                        throw SpecializedDetectionException.invalid("RTSP requires start and a valid source URL");
                } else if (!source.matches("[A-Za-z0-9]{64}"))
                    throw SpecializedDetectionException.invalid("Use an uploaded file code; local paths and HTTP media URLs are not accepted");
                if ("start".equals(operation)) {
                    URI callback = new URI(required(option, "callbackUrl"));
                    if (callback.getHost() == null || callback.getPort() > 65535
                            || !("http".equalsIgnoreCase(callback.getScheme()) || "https".equalsIgnoreCase(callback.getScheme())))
                        throw SpecializedDetectionException.invalid("callbackUrl must be an HTTP(S) URL");
                }
                streamId = null;
            }
        } catch (SpecializedDetectionException error) {
            throw error;
        } catch (Exception error) {
            throw SpecializedDetectionException.invalid("Invalid detection input");
        }
    }

    private static String required(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty())
            throw SpecializedDetectionException.invalid(key + " is required");
        return (String) value;
    }

    private static void allowed(JSONObject json, String... names) {
        Set<String> keys = new HashSet<>(Arrays.asList(names));
        for (String name : json.keySet())
            if (!keys.contains(name))
                throw SpecializedDetectionException.invalid("Unsupported detection field: " + name);
    }

    private static void number(JSONObject json, String key, double low, double high) {
        if (!json.has(key)) return;
        Object value = json.get(key);
        double n;
        try {
            n = value instanceof Boolean ? Double.NaN : Double.parseDouble(value.toString());
        } catch (Exception error) {
            n = Double.NaN;
        }
        if (Double.isNaN(n) || Double.isInfinite(n) || n < low || n > high)
            throw SpecializedDetectionException.invalid("Invalid " + key);
    }

    public boolean isControl() {
        return "status".equals(operation) || "stop".equals(operation);
    }

    public boolean isRtsp() {
        return source != null && (source.regionMatches(true, 0, "rtsp://", 0, 7) || source.regionMatches(true, 0, "rtsps://", 0, 8));
    }

    public JSONObject toUnitJSON(FileLabel label) {
        JSONArray files = new JSONArray();
        if (source != null) files.put(label == null ? source : label.toJSON());
        return new JSONObject().put("unit", UNIT).put("files", files).put("option", new JSONObject(option.toString()));
    }

    @Override
    public JSONObject toJSON() {
        return toUnitJSON(null);
    }

    @Override
    public JSONObject toCompactJSON() {
        return toJSON();
    }
}
