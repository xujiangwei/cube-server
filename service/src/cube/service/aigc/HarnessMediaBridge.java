package cube.service.aigc;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.completion.MediaProvider;
import cube.aigc.usage.*;
import cube.common.Packet;
import cube.common.entity.*;
import cube.common.action.AIGCAction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

/** Thin host adapter to the existing multimodal and specializedDetection Unit actions. */
public final class HarnessMediaBridge implements MediaProvider {
    private final AIGCService service;
    private volatile Properties config = new Properties();
    private final ConcurrentMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private static final class Pending {
        final AIGCUnit unit; final JSONObject request; final Consumer<JSONObject> listener;
        final String fileCode; final String domain; volatile JSONObject receipt; volatile boolean usageStarted, runningMarked;
        Pending(AIGCUnit unit, JSONObject request, Consumer<JSONObject> listener, String fileCode) {
            this.unit = unit; this.request = request; this.listener = listener; this.fileCode = fileCode; this.domain = request.getString("tenant");
        }
    }
    HarnessMediaBridge(AIGCService service) { this.service = service; }
    public void configure(Properties p) { Properties copy = new Properties(); copy.putAll(p); config = copy; }
    private String instance(AIGCUnit unit) { return unit.getContact().getDomain().getName() + ":" + unit.getContact().getId(); }
    public JSONArray mediaUnits() {
        JSONArray out = new JSONArray();
        if (!service.isStarted() || !Boolean.parseBoolean(config.getProperty("harness.media.dedicated", "false"))) return out;
        Set<String> names = new HashSet<>(Arrays.asList(config.getProperty("harness.media.units", "").split(",")));
        // Operators enable only warmed-up real routes; the source tree is not a readiness signal.
        for (AIGCUnit unit : service.getAllUnits()) if (names.contains(unit.getCapability().getName())) {
            out.put(new JSONObject().put("unit", unit.getCapability().getName()).put("instanceId", instance(unit))
                    .put("capacity", 1).put("ready", unit.getContext().isValid()));
        }
        return out;
    }
    public void invokeMedia(JSONObject execution, byte[] image, Consumer<JSONObject> receipt) {
        // No retry, including executor rejection. The caller already owns a durable intent.
        String id = execution.getString("requestId"); active.add(id);
        try { service.getExecutor().execute(() -> execute(execution, image, receipt)); }
        catch (RejectedExecutionException rejected) { active.remove(id); cancelled.remove(id); throw rejected; }
    }
    public void cancelMedia(String requestId) { if (active.contains(requestId)) cancelled.add(requestId); }
    private boolean expired(JSONObject request) { return cancelled.contains(request.getString("requestId")) || System.currentTimeMillis() >= request.getLong("deadlineAt"); }
    private void execute(JSONObject execution, byte[] image, Consumer<JSONObject> listener) {
        AIGCUnit selected = null;
        for (AIGCUnit unit : service.getAllUnits()) if (unit.getCapability().getName().equals(execution.getString("unit"))
                && instance(unit).equals(execution.getString("instanceId")) && unit.getContext().isValid()) selected = unit;
        JSONObject unsent = new JSONObject().put("requestId", execution.getString("requestId")).put("instanceId", execution.getString("instanceId"))
                .put("actualFinished", true).put("success", false).put("unsent", true);
        final AIGCUnit unit = selected;
        String code = execution.getString("requestId");
        long sn = execution.getLong("serviceSn");
        boolean sent = false;
        Pending p = new Pending(unit, execution, listener, code); pending.put(sn, p);
        Path temporary = null;
        try {
            if (unit == null) throw new IllegalStateException("CAPABILITY_NOT_READY");
            if (expired(execution)) throw new IllegalStateException("MEDIA_CANCELLED_BEFORE_SEND");
            if (image.length > 1048576 || image.length == 0) throw new IllegalArgumentException("MEDIA_SIZE");
            temporary = Files.createTempFile("harness-media-", execution.getString("mediaType").equals("image/png") ? ".png" : ".jpg");
            Files.write(temporary, image);
            FileLabel label = service.saveHarnessMedia(execution, code, temporary.toFile());
            if (label == null) throw new IllegalStateException("FILE_STORAGE_UNAVAILABLE");
            if (expired(execution)) throw new IllegalStateException("MEDIA_CANCELLED_BEFORE_SEND");
            JSONObject input = new JSONObject().put("unit", execution.getString("unit")).put("content", execution.getString("content"))
                    .put("files", new JSONArray().put(code)).put("option", execution.getJSONObject("option"));
            boolean specialized = execution.getString("adapter").equals("specialized-detection");
            JSONObject data;
            if (specialized) data = new SpecializedDetectionInput(input).toUnitJSON(label);
            else {
                MultimodalInput multimodal = new MultimodalInput(input);
                multimodal.fileLabels = Collections.singletonList(label); data = multimodal.toUnitJson();
            }
            UsageContext usage = UsageContext.run(execution.getString("tenant"), Long.parseLong(execution.getString("subject")), execution.getString("runId"), execution.getString("requestId"));
            service.getStorage().getUsageWriter().begin(usage, execution.getString("requestId"), unit.getCapability().getName(), unit.getQueryKey(), System.currentTimeMillis());
            p.usageStarted = true;
            if (expired(execution)) throw new IllegalStateException("MEDIA_CANCELLED_BEFORE_SEND");
            unit.setRunning(true);
            p.runningMarked = true;
            Packet packet = new Packet(sn, specialized ? AIGCAction.SpecializedDetection.name : AIGCAction.Multimodal.name, data);
            sent = true; // A missing ACK after this point is unknown, even if transmit returns null.
            ActionDialect response = service.getCellet().transmit(unit.getContext(), packet.toDialect(), 180000, sn);
            if (response != null) onResponse(unit.getContext(), response);
        } catch (Exception failure) {
            if (!sent) {
                if (p.runningMarked) { unit.setRunning(false); p.runningMarked = false; }
                p.receipt = unsent.put("payload", new JSONObject()).put("finishedAt", System.currentTimeMillis());
                reconcile(); // Persist even a known-unsent receipt before releasing the host reservation.
            }
            // Once sent, retain the file, pending route and the host's durable reservation.
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }
    public void onResponse(TalkContext context, ActionDialect dialect) {
        Packet packet;
        try { packet = new Packet(dialect); } catch (Exception invalid) { return; }
        Pending p = pending.get(packet.sn);
        if (p == null || p.unit == null || p.unit.getContext() != context) return;
        String expected = p.request.getString("adapter").equals("specialized-detection") ? AIGCAction.SpecializedDetection.name : AIGCAction.Multimodal.name;
        if (!expected.equals(packet.name) || !packet.data.has("code")) return;
        synchronized (p) {
            if (p.receipt != null) return;
            JSONObject payload = packet.data.optJSONObject("data");
            payload = payload == null ? new JSONObject() : new JSONObject(payload.toString());
            payload.remove("thought");
            p.receipt = new JSONObject().put("requestId", p.request.getString("requestId")).put("instanceId", p.request.getString("instanceId"))
                    .put("serviceSn", packet.sn).put("actualFinished", true).put("success", Packet.extractCode(packet) == 0).put("payload", payload)
                    .put("finishedAt", System.currentTimeMillis());
        }
        reconcile();
    }
    public synchronized void reconcile() {
        for (Map.Entry<Long,Pending> entry : pending.entrySet()) {
            Pending p = entry.getValue(); JSONObject receipt = p.receipt;
            if (receipt == null) continue;
            try {
                long[] tokens = UsageTokens.read(receipt.getJSONObject("payload"));
                if (p.usageStarted) service.getStorage().getUsageWriter().finish(UsageLedger.evidence(p.request.getString("requestId"), 2,
                        receipt.getBoolean("success") ? "succeeded" : "failed", tokens == null ? "unknown" : "reported",
                        tokens == null ? null : tokens[0], tokens == null ? null : tokens[1], receipt.getLong("finishedAt")));
                if (p.runningMarked) { p.unit.setRunning(false); p.runningMarked = false; }
                p.listener.accept(receipt); // Failed persistence stays queued; never repeat inference.
                service.deleteFile(p.domain, p.fileCode); pending.remove(entry.getKey(), p);
                active.remove(p.request.getString("requestId")); cancelled.remove(p.request.getString("requestId"));
            } catch (Exception retryReceiptOnly) { /* retried by AIGC onTick, not by re-dispatch */ }
        }
    }
}
