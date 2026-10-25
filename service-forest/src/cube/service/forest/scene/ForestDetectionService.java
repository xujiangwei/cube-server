/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.forest.scene;

import cube.aigc.spi.AIGCHost;
import cube.aigc.spi.PluginCallException;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.entity.FileLabel;
import cube.common.state.AIGCStateCode;
import cube.service.forest.ForestConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Iterator;

/**
 * File ownership, input limits and the forest result contract.
 */
public final class ForestDetectionService {
    private final AIGCHost host;
    private final ForestConfig config;

    public ForestDetectionService(AIGCHost host, ForestConfig config) {
        this.host = host;
        this.config = config;
    }

    private static PluginCallException error(AIGCStateCode code, int http, String text) {
        return new PluginCallException(code, http, text);
    }

    public JSONObject detect(AuthToken token, JSONObject request) {
        return detect(token, request, config.timeoutMs);
    }

    /**
     * The module deducts time already spent waiting in its bounded queue.
     */
    public JSONObject detect(AuthToken token, JSONObject request, long timeoutMs) {
        if (timeoutMs <= 0) throw error(AIGCStateCode.UnitError, 504, "Forest request expired in queue");
        if (token == null) throw error(AIGCStateCode.NoToken, 401, "Valid token required");
        if (request == null) throw error(AIGCStateCode.InvalidParameter, 400, "Request body required");
        for (String key : request.keySet())
            if (!key.equals("fileCode") && !key.equals("scoreThreshold"))
                throw error(AIGCStateCode.InvalidParameter, 400, "Unknown forest request field");
        Object fileCode = request.opt("fileCode");
        if (!(fileCode instanceof String) || !((String) fileCode).matches("[A-Za-z0-9]{64}"))
            throw error(AIGCStateCode.InvalidParameter, 400, "A valid uploaded fileCode is required");
        Object score = request.has("scoreThreshold") ? request.opt("scoreThreshold") : config.threshold;
        if (!(score instanceof Number))
            throw error(AIGCStateCode.InvalidParameter, 400, "scoreThreshold must be numeric");
        double threshold = ((Number) score).doubleValue();
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw error(AIGCStateCode.InvalidParameter, 400, "scoreThreshold must be between 0 and 1");
        long started = System.nanoTime();
        FileLabel label = host.getFile(token.getDomain(), (String) fileCode);
        if (label == null) throw error(AIGCStateCode.NotFound, 404, "Image not found");
        if (!token.getDomain().equals(label.getDomain().getName()) || label.getOwnerId() == null
                || label.getOwnerId().longValue() != token.getContactId())
            throw error(AIGCStateCode.IllegalOperation, 403, "Image access denied");
        if (label.getFileSize() <= 0 || label.getFileSize() > config.maxBytes)
            throw error(AIGCStateCode.ContentLengthOverflow, 413, "Image exceeds byte limit");
        File image = host.loadFile(token.getDomain(), (String) fileCode);
        validateImage(image);
        long remaining = timeoutMs - (System.nanoTime() - started) / 1000000;
        if (remaining <= 0) throw error(AIGCStateCode.UnitError, 504, "Image preparation timed out");
        JSONObject input = new JSONObject().put("content", "").put("history", new JSONArray())
                .put("files", new JSONArray().put(label.toJSON()))
                .put("option", new JSONObject().put("mode", "tile").put("score_threshold", threshold).put("wait", false));
        Packet reply = new Packet(host.invokePluginUnit(ForestConfig.UNIT, input, remaining));
        int code = Packet.extractCode(reply);
        if (code != 0) throw error(AIGCStateCode.parse(code), httpStatus(code), "OmniForest detection failed");
        JSONObject result = Packet.extractDataPayload(reply);
        validateResult(result, config.maxPixels);
        return result;
    }

    private void validateImage(File file) {
        if (file == null || !file.isFile()) throw error(AIGCStateCode.FileError, 422, "Image cannot be read");
        if (file.length() > config.maxBytes)
            throw error(AIGCStateCode.ContentLengthOverflow, 413, "Image exceeds byte limit");
        try (ImageInputStream stream = ImageIO.createImageInputStream(file)) {
            if (stream == null) throw error(AIGCStateCode.FileError, 422, "Invalid image");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw error(AIGCStateCode.FileError, 422, "Invalid image");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("jpeg") && !format.equalsIgnoreCase("png"))
                    throw error(AIGCStateCode.InvalidParameter, 400, "Only JPEG and PNG are supported");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > config.maxPixels)
                    throw error(AIGCStateCode.ContentLengthOverflow, 413, "Image exceeds pixel limit");
            } finally {
                reader.dispose();
            }
        } catch (PluginCallException e) {
            throw e;
        } catch (Exception e) {
            throw error(AIGCStateCode.FileError, 422, "Invalid image");
        }
    }

    public static void validateResult(JSONObject result, long maxPixels) {
        try {
            if (!(result.opt("response") instanceof String) || !(result.opt("thought") instanceof String)
                    || result.optJSONObject("performance") == null) throw new IllegalArgumentException();
            JSONObject payload = result.getJSONObject("resultPayload");
            int width = integer(payload, "width"), height = integer(payload, "height");
            int count = integer(payload, "count");
            if (width <= 0 || height <= 0 || (long) width * height > maxPixels || count < 0)
                throw new IllegalArgumentException();
            JSONArray refs = payload.getJSONArray("refs");
            long boxes = 0;
            for (int i = 0; i < refs.length(); i++) {
                JSONObject ref = refs.getJSONObject(i);
                if (!"tree".equals(ref.getString("content")) || ref.getJSONArray("points").length() != 0)
                    throw new IllegalArgumentException();
                JSONArray items = ref.getJSONArray("boxes");
                boxes += items.length();
                for (int j = 0; j < items.length(); j++) {
                    JSONObject box = items.getJSONObject(j);
                    double x1 = coordinate(box, "x1"), y1 = coordinate(box, "y1");
                    double x2 = coordinate(box, "x2"), y2 = coordinate(box, "y2");
                    if (!(0 <= x1 && x1 < x2 && x2 <= width && 0 <= y1 && y1 < y2 && y2 <= height))
                        throw new IllegalArgumentException();
                }
            }
            if (boxes != count) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw error(AIGCStateCode.UnitError, 502, "Invalid OmniForest detection result");
        }
    }

    private static int integer(JSONObject json, String key) {
        Object value = json.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException();
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number > Integer.MAX_VALUE || number < Integer.MIN_VALUE)
            throw new IllegalArgumentException();
        return (int) number;
    }

    private static double coordinate(JSONObject json, String key) {
        Object value = json.get(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()))
            throw new IllegalArgumentException();
        return ((Number) value).doubleValue();
    }

    public JSONObject capability() {
        int providers = host.countPluginUnits(ForestConfig.UNIT);
        return new JSONObject().put("plugin", "OmniForest").put("unit", ForestConfig.UNIT)
                .put("online", providers > 0).put("providers", providers).put("readiness", "checked-on-invoke")
                .put("formats", new JSONArray(Arrays.asList("jpeg", "png")))
                .put("maxFiles", 1).put("maxBytes", config.maxBytes).put("maxPixels", config.maxPixels)
                .put("scoreThreshold", config.threshold).put("mode", "tile");
    }

    public static int httpStatus(int code) {
        switch (code) {
            case 0:
                return 200;
            case 1:
                return 400;
            case 3:
                return 404;
            case 7:
            case 26:
                return 429;
            case 21:
            case 22:
                return 401;
            case 25:
                return 403;
            case 30:
            case 33:
                return 503;
            case 32:
                return 422;
            case 41:
                return 413;
            default:
                return 502;
        }
    }
}
