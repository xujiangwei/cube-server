package cube.service.harness.execution;

import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.runtime.ExecutionProfiles;
import cube.service.harness.store.JdbcRunStore;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Trusted server-side counting. Worker supplied token counts never authorize inference. */
public final class ModelBudgets {
    /** Implementations must bind model, adapter, tokenizer assets and chat template versions.
     * countContext includes the full adapter prompt, tools and chat framing, not serialized JSON tokens.
     * countWire counts the actual inference prompt, including format-repair messages.
     * Install only counters verified against the deployed backend; no generic counter is certified here.
     * Implementations must be thread safe, deterministic and perform no network or asset loading during counting.
     */
    public interface Counter {
        boolean supports(JSONObject model);
        long countContext(JSONObject model, JSONObject request);
        long countWire(JSONObject model, JSONObject wire);
    }
    private final List<Counter> counters;
    public ModelBudgets(Counter... counters) { this.counters = Arrays.asList(counters); }
    public static ModelBudgets load(Properties properties) {
        List<Counter> result = new ArrayList<>();
        for (String name : properties.getProperty("model.token.counter.classes", "").split(",")) {
            if (name.trim().isEmpty()) continue;
            try { result.add((Counter)Class.forName(name.trim()).getDeclaredConstructor().newInstance()); }
            catch (Exception e) { throw new IllegalArgumentException("MODEL_COUNTER_CONFIGURATION_INVALID", e); }
        }
        return new ModelBudgets(result.toArray(new Counter[0]));
    }
    public boolean exact(JSONObject model) {
        return !Arrays.asList("transport-bytes-v1", "utf8-bytes-v1").contains(model.getJSONObject("tokenizer").getString("id"));
    }
    public Counter require(JSONObject model) {
        Counter selected = null;
        for (Counter counter : counters) if (supports(counter, model)) {
            if (selected != null) throw RuntimeSupport.error("MODEL_COUNTER_AMBIGUOUS");
            selected = counter;
        }
        if (selected == null) throw RuntimeSupport.error("MODEL_COUNTER_UNAVAILABLE");
        return selected;
    }
    private boolean supports(Counter counter, JSONObject model) {
        try { return counter.supports(RuntimeSupport.copy(model)); }
        catch (RuntimeException error) { throw RuntimeSupport.error("MODEL_COUNTER_INVALID"); }
    }
    private long count(JSONObject model, JSONObject request, boolean wire) {
        Counter counter = require(model);
        try { return valid(wire ? counter.countWire(RuntimeSupport.copy(model), RuntimeSupport.copy(request))
                : counter.countContext(RuntimeSupport.copy(model), RuntimeSupport.copy(request))); }
        catch (RuntimeException error) { throw RuntimeSupport.error("MODEL_COUNTER_INVALID"); }
    }
    private static long valid(long value) {
        if (value < 0 || value > 1000000000L) throw RuntimeSupport.error("MODEL_COUNTER_INVALID");
        return value;
    }
    public long context(JSONObject model, JSONObject request, int renderedBytes) {
        return exact(model) ? count(model, request, false)
                : (long)renderedBytes + model.getInt("templateOverhead");
    }
    /** Called after job identity/profile/idempotency checks, before any queue entry or dispatch. */
    public void admitWire(JSONObject pin, JSONObject wire) {
        JSONObject model = pin.getJSONObject("model"), policy = ExecutionProfiles.execution(pin).getJSONObject("contextPolicy");
        if (!exact(model) && !policy.optBoolean("finalWireGuard", false)) return; // Preserve older pinned runs.
        int bytes = JdbcRunStore.canonical(wire).getBytes(StandardCharsets.UTF_8).length;
        long tokens = exact(model) ? count(model, wire, true)
                : (long)bytes + model.getInt("templateOverhead");
        if (bytes > policy.getInt("maxInputBytes") || tokens > (long)model.getInt("maxContextTokens") - wire.optLong("max_tokens", wire.optLong("max_completion_tokens", 0)))
            throw RuntimeSupport.error("INFERENCE_CONTEXT_BUDGET_EXCEEDED");
    }
}
