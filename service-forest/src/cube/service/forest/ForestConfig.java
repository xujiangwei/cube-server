package cube.service.forest;

import cube.aigc.spi.AIGCHost;
import org.json.JSONObject;

/** Configuration is private to the forest module; no database is required. */
public final class ForestConfig {
    public static final String UNIT = "forest-detection";
    public final long maxBytes, maxPixels, timeoutMs;
    public final int concurrency, queueSize;
    public final double threshold;

    public ForestConfig(JSONObject json) {
        maxBytes = json.optLong("maxBytes", 20971520);
        maxPixels = json.optLong("maxPixels", 20000000);
        timeoutMs = json.optLong("timeoutMs", 60000);
        concurrency = json.optInt("concurrency", 4);
        queueSize = json.optInt("queueSize", 8);
        threshold = json.optDouble("scoreThreshold", 0.3);
        if (maxBytes <= 0 || maxPixels <= 0 || timeoutMs <= 0 || timeoutMs > 75000
                || concurrency <= 0 || concurrency > 32 || queueSize <= 0 || queueSize > 256
                || !Double.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("Invalid forest configuration");
    }

    public static ForestConfig read(AIGCHost host) {
        String value = host.readModuleResource("forest", "config.json");
        return new ForestConfig(value == null ? new JSONObject() : new JSONObject(value));
    }
}
