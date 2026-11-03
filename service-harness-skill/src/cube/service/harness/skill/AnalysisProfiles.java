package cube.service.harness.skill;

import cube.service.harness.protocol.Contract;
import cube.service.harness.store.StoreException;
import java.util.*;
import org.json.*;

/** Maps immutable business aliases to existing Unit options, never to the run's main model. */
public final class AnalysisProfiles {
    private AnalysisProfiles() {}
    public static void validate(JSONObject config) {
        Contract.validate(config, AbstractProvider.schema("capability", "analysis-profiles"));
        Set<String> seen = new HashSet<>();
        JSONArray profiles = config.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject p = profiles.getJSONObject(i);
            if (!seen.add(p.getString("id"))) throw new StoreException("ANALYSIS_PROFILE_INVALID");
            String unit = p.getString("unit"), adapter = p.getString("adapter"), kind;
            if (adapter.equals("multimodal") && unit.equals("OmniMultimodal")) kind = "options-multimodal";
            else if (adapter.equals("multimodal") && unit.equals("OmniPerception")) kind = "options-perception";
            else if (adapter.equals("specialized-detection") && unit.equals("SpecializedDetection")) kind = "options-specialized";
            else throw new StoreException("ANALYSIS_PROFILE_INVALID");
            JSONObject options = ConnectorConfig.arguments(p.getString("optionsJson"));
            Contract.validate(options, AbstractProvider.schema("capability", kind));
            if (options.optBoolean("sliced") && !options.optString("profile").equals("uav-fire-smoke")) throw new StoreException("ANALYSIS_PROFILE_INVALID");
        }
    }
}
