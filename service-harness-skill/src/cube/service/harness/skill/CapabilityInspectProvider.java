package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import org.json.*;
import java.util.*;

/** Discover authorized logical analysis profiles without acquiring capacity or executing inference. */
public final class CapabilityInspectProvider extends AbstractProvider {
    private final Set<String> allowed;
    public CapabilityInspectProvider(Properties properties) {
        super("capabilities", "common.capabilities.inspect", "capability.read", properties);
        allowed = new HashSet<>();
        for (String unit : properties.getProperty("capability.allowed.units", "").split(","))
            if (!unit.trim().isEmpty()) allowed.add(unit.trim());
    }
    @Override public Set<String> configurationFiles() { return Collections.singleton("analysis-profiles.json"); }
    @Override public void validateConfiguration(String path, JSONObject config) { AnalysisProfiles.validate(config); }
    @Override protected JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception {
        return inspect(context.resource(args.getString("skill"), "analysis-profiles.json"), context.mediaUnits());
    }
    JSONObject inspect(JSONObject config, JSONArray units) {
        AnalysisProfiles.validate(config);
        JSONArray result = new JSONArray(), profiles = config.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            String unit = profile.getString("unit"), status = "unavailable";
            if (!allowed.contains(unit)) status = "disabled";
            else {
                JSONObject option = CapabilityInvokeProvider.unitOptions(profile);
                for (int j = 0; j < units.length(); j++) {
                    JSONObject instance = units.getJSONObject(j);
                    if (!unit.equals(instance.optString("unit")) || !instance.optBoolean("ready")) continue;
                    if (unit.equals("SpecializedDetection") && (!cube.aigc.SpecializedDetectionCapabilities.ready(instance)
                            || !cube.aigc.SpecializedDetectionCapabilities.supports(instance, option.getString("profile"), option.optBoolean("sliced")))) continue;
                    if (instance.optInt("capacity") > 0) status = "ready";
                }
            }
            result.put(new JSONObject().put("profileId", profile.getString("id")).put("mediaTypes", profile.getJSONArray("mediaTypes"))
                    .put("status", status));
        }
        return new JSONObject().put("profiles", result).put("availability", "readiness-only-not-capacity");
    }
}
