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
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Admission control and result contract of the forest scenario: verifies the caller, the file
 * ownership and the input limits, forwards the request to the OmniForest plugin unit and checks the
 * reply before it reaches the caller. No inference happens in this class.
 */
public final class ForestDetectionService {

    /**
     * Uploaded file codes are 64 alphanumeric characters.
     */
    private static final Pattern FILE_CODE = Pattern.compile("[A-Za-z0-9]{64}");

    /**
     * The only fields a detection request may carry; anything else is rejected as unknown.
     */
    private static final Set<String> ALLOWED_FIELDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("fileCode", "scoreThreshold")));

    private final AIGCHost host;

    private final ForestConfig config;

    public ForestDetectionService(AIGCHost host, ForestConfig config) {
        this.host = host;
        this.config = config;
    }

    /**
     * Detects tree crowns using the timeout from the module configuration.
     */
    public JSONObject detect(AuthToken token, JSONObject request) {
        return detect(token, request, this.config.timeoutMs);
    }

    /**
     * Detects tree crowns.
     *
     * <p>{@code timeoutMs} is the budget left for this call: the caller has already deducted the
     * time the request spent waiting in its bounded queue. File preparation consumes the same
     * budget, so whatever remains is what the plugin unit receives.</p>
     */
    public JSONObject detect(AuthToken token, JSONObject request, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw fail(AIGCStateCode.UnitError, 504, "Forest request expired in queue");
        }

        requireToken(token);
        if (request == null) {
            throw fail(AIGCStateCode.InvalidParameter, 400, "Request body required");
        }
        requireAllowedFields(request);
        String fileCode = requireFileCode(request);
        double threshold = requireThreshold(request);

        long started = System.nanoTime();
        FileLabel label = requireOwnedImage(token, fileCode);
        validateImage(this.host.loadFile(token.getDomain(), fileCode));
        long remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (remaining <= 0) {
            throw fail(AIGCStateCode.UnitError, 504, "Image preparation timed out");
        }

        JSONObject result = invoke(label, threshold, remaining);
        validateResult(result, this.config.maxPixels);
        return result;
    }

    /**
     * Rejects callers without a token before any resource is touched.
     */
    private static void requireToken(AuthToken token) {
        if (token == null) {
            throw fail(AIGCStateCode.NoToken, 401, "Valid token required");
        }
    }

    /**
     * Keeps the request surface at the documented two fields.
     */
    private static void requireAllowedFields(JSONObject request) {
        for (String field : request.keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw fail(AIGCStateCode.InvalidParameter, 400, "Unknown forest request field");
            }
        }
    }

    private static String requireFileCode(JSONObject request) {
        Object value = request.opt("fileCode");
        if (!(value instanceof String) || !FILE_CODE.matcher((String) value).matches()) {
            throw fail(AIGCStateCode.InvalidParameter, 400, "A valid uploaded fileCode is required");
        }
        return (String) value;
    }

    /**
     * An absent threshold falls back to the module configuration.
     */
    private double requireThreshold(JSONObject request) {
        Object value = request.has("scoreThreshold") ? request.opt("scoreThreshold") : this.config.threshold;
        if (!(value instanceof Number)) {
            throw fail(AIGCStateCode.InvalidParameter, 400, "scoreThreshold must be numeric");
        }
        double threshold = ((Number) value).doubleValue();
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw fail(AIGCStateCode.InvalidParameter, 400, "scoreThreshold must be between 0 and 1");
        }
        return threshold;
    }

    /**
     * File ownership is the only access control for uploaded images, so the domain and the contact
     * of the token must both match the label.
     */
    private FileLabel requireOwnedImage(AuthToken token, String fileCode) {
        FileLabel label = this.host.getFile(token.getDomain(), fileCode);
        if (label == null) {
            throw fail(AIGCStateCode.NotFound, 404, "Image not found");
        }

        String tokenDomain = token.getDomain();
        String labelDomain = label.getDomain() == null ? null : label.getDomain().getName();
        Long ownerId = label.getOwnerId();
        if (tokenDomain == null || !tokenDomain.equals(labelDomain)
                || ownerId == null || ownerId.longValue() != token.getContactId()) {
            throw fail(AIGCStateCode.IllegalOperation, 403, "Image access denied");
        }

        if (label.getFileSize() <= 0 || label.getFileSize() > this.config.maxBytes) {
            throw fail(AIGCStateCode.ContentLengthOverflow, 413, "Image exceeds byte limit");
        }
        return label;
    }

    /**
     * Opens the stored file far enough to prove it is a decodable JPEG or PNG within the limits.
     * The image is never fully decoded and never written back.
     */
    private void validateImage(File file) {
        if (file == null || !file.isFile()) {
            throw fail(AIGCStateCode.FileError, 422, "Image cannot be read");
        }
        if (file.length() > this.config.maxBytes) {
            throw fail(AIGCStateCode.ContentLengthOverflow, 413, "Image exceeds byte limit");
        }

        try (ImageInputStream stream = ImageIO.createImageInputStream(file)) {
            if (stream == null) {
                throw fail(AIGCStateCode.FileError, 422, "Invalid image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw fail(AIGCStateCode.FileError, 422, "Invalid image");
            }

            ImageReader reader = readers.next();
            try {
                requireSupportedFormat(reader.getFormatName());
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > this.config.maxPixels) {
                    throw fail(AIGCStateCode.ContentLengthOverflow, 413, "Image exceeds pixel limit");
                }
            } finally {
                reader.dispose();
            }
        } catch (PluginCallException e) {
            throw e;
        } catch (Exception e) {
            throw fail(AIGCStateCode.FileError, 422, "Invalid image");
        }
    }

    /**
     * The Python contract only decodes JPEG and PNG.
     */
    private static void requireSupportedFormat(String format) {
        if (format == null) {
            throw fail(AIGCStateCode.FileError, 422, "Invalid image");
        }
        if (!format.equalsIgnoreCase("jpeg") && !format.equalsIgnoreCase("png")) {
            throw fail(AIGCStateCode.InvalidParameter, 400, "Only JPEG and PNG are supported");
        }
    }

    /**
     * Sends the prepared label to the plugin unit and unwraps a successful reply. A non-zero module
     * code keeps the business state and the matching HTTP status.
     */
    private JSONObject invoke(FileLabel label, double threshold, long timeoutMs) {
        JSONObject option = new JSONObject().put("action", "detection").put("mode", "tile")
                .put("score_threshold", threshold).put("wait", false);
        JSONObject input = new JSONObject().put("content", "").put("history", new JSONArray())
                .put("files", new JSONArray().put(label.toJSON()))
                .put("option", option);

        Packet reply = new Packet(this.host.invokePluginUnit(ForestConfig.UNIT, input, timeoutMs));
        int code = Packet.extractCode(reply);
        if (code != 0) {
            throw fail(AIGCStateCode.parse(code), httpStatus(code), "OmniForest detection failed");
        }
        return Packet.extractDataPayload(reply);
    }

    /**
     * Rejects any reply that does not match the contract documented in the module README: every
     * reference is a tree without points, every box lies inside the frame and the box count adds up.
     */
    public static void validateResult(JSONObject result, long maxPixels) {
        try {
            requireResultEnvelope(result);
            JSONObject payload = result.getJSONObject("resultPayload");
            int width = integer(payload, "width");
            int height = integer(payload, "height");
            int count = integer(payload, "count");
            if (width <= 0 || height <= 0 || (long) width * height > maxPixels) {
                throw new IllegalArgumentException("Frame size out of range");
            }
            if (count < 0) {
                throw new IllegalArgumentException("Negative box count");
            }
            requireTreeReferences(payload.getJSONArray("refs"), count, width, height);
        } catch (RuntimeException e) {
            throw fail(AIGCStateCode.UnitError, 502, "Invalid OmniForest detection result");
        }
    }

    private static void requireResultEnvelope(JSONObject result) {
        if (result == null
                || !(result.opt("response") instanceof String)
                || !(result.opt("thought") instanceof String)
                || result.optJSONObject("performance") == null) {
            throw new IllegalArgumentException("Incomplete result envelope");
        }
    }

    /**
     * A reference must describe one or more tree boxes and must not piggyback point prompts.
     */
    private static void requireTreeReferences(JSONArray refs, int count, int width, int height) {
        long boxes = 0;
        for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.getJSONObject(i);
            if (!"tree".equals(ref.getString("content")) || ref.getJSONArray("points").length() != 0) {
                throw new IllegalArgumentException("Unexpected reference: " + i);
            }

            JSONArray items = ref.getJSONArray("boxes");
            boxes += items.length();
            for (int j = 0; j < items.length(); j++) {
                requireBox(items.getJSONObject(j), width, height);
            }
        }
        if (boxes != count) {
            throw new IllegalArgumentException("Box count mismatch: " + boxes + " != " + count);
        }
    }

    /**
     * Boxes are half-open rectangles in pixels with a positive area.
     */
    private static void requireBox(JSONObject box, int width, int height) {
        double x1 = coordinate(box, "x1"), y1 = coordinate(box, "y1");
        double x2 = coordinate(box, "x2"), y2 = coordinate(box, "y2");
        if (!(0 <= x1 && x1 < x2 && x2 <= width && 0 <= y1 && y1 < y2 && y2 <= height)) {
            throw new IllegalArgumentException("Box outside the frame");
        }
    }

    /**
     * Reads a strictly integral number; JSON writers may still send {@code 100.0}.
     */
    private static int integer(JSONObject json, String key) {
        Object value = json.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Not a number: " + key);
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Not an integer: " + key);
        }
        return (int) number;
    }

    private static double coordinate(JSONObject json, String key) {
        Object value = json.get(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) {
            throw new IllegalArgumentException("Not a coordinate: " + key);
        }
        return ((Number) value).doubleValue();
    }

    /**
     * Reports the unit state with the capability contract appended to it.
     */
    public JSONObject capability() {
        JSONObject status = this.host.getPluginUnitStatus(ForestConfig.UNIT);
        return status.put("plugin", "OmniForest").put("unit", ForestConfig.UNIT)
                .put("actions", new JSONArray(Arrays.asList("detection")))
                .put("formats", new JSONArray(Arrays.asList("jpeg", "png")))
                .put("maxFiles", 1).put("maxBytes", this.config.maxBytes).put("maxPixels", this.config.maxPixels)
                .put("scoreThreshold", this.config.threshold).put("mode", "tile");
    }

    /**
     * Maps a module state code to the HTTP status returned to the API caller.
     */
    public static int httpStatus(int code) {
        switch (AIGCStateCode.parse(code)) {
            case Ok:
                return 200;
            case InvalidParameter:
                return 400;
            case NotFound:
                return 404;
            case Processing:
            case Busy:
                return 429;
            case InconsistentToken:
            case NoToken:
                return 401;
            case IllegalOperation:
                return 403;
            case UnitNoReady:
            case ModuleNotLoaded:
                return 503;
            case FileError:
                return 422;
            case ContentLengthOverflow:
                return 413;
            default:
                return 502;
        }
    }

    /**
     * Builds the transport error used by every rejection in this class.
     */
    private static PluginCallException fail(AIGCStateCode code, int http, String message) {
        return new PluginCallException(code, http, message);
    }
}
