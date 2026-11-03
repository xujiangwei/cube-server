package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.json.*;

/** Existing Unit invocation with durable physical receipts, not a new visual model. */
public final class CapabilityInvokeProvider extends AbstractProvider {
    private final DataNetwork network;
    private final Set<String> allowed;
    private final int timeout, maxPixels;
    public CapabilityInvokeProvider(Properties p) {
        super("capability", "common.capability.invoke", "capability.invoke", p);
        network = new DataNetwork(p);
        allowed = new HashSet<>(Arrays.asList(p.getProperty("capability.allowed.units", "").split(",")));
        timeout = number(p, "capability.timeout.millis", 20000, 100, 20000);
        maxPixels = number(p, "image.max.pixels", 4000000, 1, 16000000);
    }
    public boolean independentExecution() { return true; }
    public Set<String> configurationFiles() { return Collections.singleton("analysis-profiles.json"); }
    public void validateConfiguration(String path, JSONObject config) { AnalysisProfiles.validate(config); }
    public void preflightConfiguration(cube.service.harness.execution.QueryProvider.ProviderInvocationLookup lookup, String path, JSONObject config) {
        validateConfiguration(path, config);
        JSONArray units = lookup.mediaUnits(), profiles = config.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            String name = profiles.getJSONObject(i).getString("unit"); boolean ready = false;
            for (int j = 0; j < units.length(); j++) if (units.getJSONObject(j).getString("unit").equals(name) && units.getJSONObject(j).getBoolean("ready") && units.getJSONObject(j).getInt("capacity") > 0) ready = true;
            if (!allowed.contains(name) || !ready) throw new StoreException("CAPABILITY_NOT_READY");
        }
    }
    public boolean reconcile(ProviderInvocation c) { return c.mediaFinished(); }
    protected JSONObject execute(ProviderInvocation c, JSONObject args) throws Exception {
        String skill = args.getString("skill");
        JSONObject config = c.resource(skill, "analysis-profiles.json"); AnalysisProfiles.validate(config);
        JSONObject profile = ConnectorConfig.select(config, "profiles", args.getString("profileId"));
        if (!allowed.contains(profile.getString("unit"))) throw new StoreException("CAPABILITY_NOT_READY");
        JSONObject media = c.confirmed("media", args.getString("mediaRef"));
        if (!media.getString("skill").equals(skill)) throw new StoreException("MEDIA_REF_FORBIDDEN");
        if (!profile.getJSONArray("mediaTypes").toList().contains(media.getString("mediaType"))) throw new StoreException("MEDIA_TYPE_UNSUPPORTED");
        JSONObject connectors = c.resource(skill, "connectors.json"); ConnectorConfig.validate(connectors);
        JSONObject connector = ConnectorConfig.select(connectors, "connectors", media.getString("connectorId"));
        byte[] bytes;
        if (media.has("inputId")) bytes = c.input(media.getString("inputId"), 1048576, Collections.singleton("application/octet-stream"));
        else {
            if (!media.getString("sourceRef").equals(connector.getString("sourceRef"))) throw new StoreException("MEDIA_REF_FORBIDDEN");
            NetClient.Response raw = network.get(c, media.getString("sourceRef"), connector.getJSONObject("media").getJSONObject("request"),
                    new JSONObject().put("id", media.getString("mediaId")).put("version", media.getString("mediaVersion")), "media", new JSONObject(), 1048576);
            if (!raw.media().equals(media.getString("mediaType"))) throw new StoreException("MEDIA_VERSION_CHANGED");
            bytes = raw.body;
        }
        inspect(bytes, media.getString("mediaType"), maxPixels);
        String sha = RuntimeSupport.sha(bytes);
        c.remember("media-bytes", args.getString("mediaRef"), new JSONObject().put("sha256", sha));
        JSONObject request = new JSONObject().put("unit", profile.getString("unit")).put("adapter", profile.getString("adapter"))
                .put("option", ConnectorConfig.arguments(profile.getString("optionsJson"))).put("content", args.getString("content"))
                .put("tenant", c.tenant).put("subject", c.subject).put("runId", c.runId).put("sha256", sha).put("mediaType", media.getString("mediaType"))
                .put("deadlineAt", System.currentTimeMillis() + timeout);
        JSONObject execution = c.reserveMedia(profile.getString("unit"), request);
        CompletableFuture<JSONObject> response = new CompletableFuture<>();
        c.invokeMedia(execution, bytes, response::complete);
        // A timeout throws a non-StoreException: Broker records unknown; no automatic re-dispatch.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        JSONObject receipt = null;
        while (receipt == null) {
            if (c.cancelled()) { c.cancelMedia(); throw new IOException("MEDIA_EXECUTION_CANCELLED_WITHOUT_PHYSICAL_RECEIPT"); }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) { c.cancelMedia(); throw new TimeoutException("MEDIA_EXECUTION_UNKNOWN"); }
            try { receipt = response.get(Math.min(100, remaining), TimeUnit.MILLISECONDS); }
            catch (TimeoutException pending) { /* continue checking cancellation and the same deadline */ }
        }
        if (!receipt.optBoolean("success")) throw new StoreException("CAPABILITY_FAILED");
        JSONObject payload = receipt.getJSONObject("payload");
        JSONArray warnings = new JSONArray().put("OBSERVATION_ONLY_NO_BUSINESS_ABNORMALITY_DECISION");
        if (media.getString("timeTrust").equals("provider-declared")) warnings.put("CAPTURE_TIME_PROVIDER_DECLARED");
        JSONObject out = new JSONObject().put("profileId", args.getString("profileId")).put("mediaRef", args.getString("mediaRef"))
                .put("unit", profile.getString("unit")).put("answer", payload.optString("response", ""))
                .put("payloadJson", payload.optJSONObject("resultPayload") == null ? "{}" : payload.getJSONObject("resultPayload").toString()).put("warnings", warnings);
        validateResult(args, out);
        JSONObject provenance = new JSONObject().put("schemaVersion", 1).put("kind", "media-source")
                .put("mediaRef", args.getString("mediaRef")).put("mediaId", media.getString("mediaId")).put("mediaVersion", media.getString("mediaVersion"))
                .put("mediaType", media.getString("mediaType")).put("sha256", sha).put("source", media.getJSONObject("record"))
                .put("captureTimeTrust", media.getString("timeTrust")).put("profileId", args.getString("profileId"))
                .put("requestId", execution.getString("requestId")).put("instanceId", execution.getString("instanceId"))
                .put("analysisResultSha256", RuntimeSupport.sha(RuntimeSupport.bytes(out)));
        if (media.has("sourceRef")) provenance.put("sourceRef", media.getString("sourceRef"));
        if (media.has("inputId")) provenance.put("inputId", media.getString("inputId"));
        c.textArtifact("media-source.json", provenance.toString());
        return out;
    }
    static void inspect(byte[] bytes, String mediaType, int maxPixels) throws Exception {
        if (bytes.length < 8 || bytes.length > 1048576) throw new StoreException("MEDIA_IMAGE_INVALID");
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new StoreException("MEDIA_IMAGE_INVALID");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(mediaType.equals("image/jpeg") && format.equals("jpeg")) && !(mediaType.equals("image/png") && format.equals("png"))) throw new StoreException("MEDIA_IMAGE_INVALID");
                long width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * height > maxPixels) throw new StoreException("MEDIA_PIXEL_LIMIT");
                if (reader.read(0) == null) throw new StoreException("MEDIA_IMAGE_INVALID");
            } finally { reader.dispose(); }
        } catch (IOException invalid) { throw new StoreException("MEDIA_IMAGE_INVALID"); }
    }
    public void close() { network.close(); }
}
