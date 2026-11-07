/*
 * This source file is part of Cube.
 * Copyright (c) 2023-2026 Ambrose Xu.
 */
package cube.service.harness.skill;

import org.json.JSONObject;

/** Verifies the boundary between Harness profile options and the Omni wire contract. */
public final class OmniOptionsTest {
    public static void main(String[] args) {
        JSONObject perception = profile("OmniPerception", "multimodal", "{\"task\":\"counting\",\"lang\":\"en\"}");
        JSONObject option = CapabilityInvokeProvider.unitOptions(perception);
        check("analyze".equals(option.getString("operation")));
        check("counting".equals(option.getString("task")));
        check("en".equals(option.getJSONObject("parameters").getString("lang")));
        check(!option.has("lang"));
        check("counting".equals(new JSONObject(perception.getString("optionsJson")).getString("task")));
        JSONObject multimodal = CapabilityInvokeProvider.unitOptions(profile("OmniMultimodal", "multimodal", "{}"));
        check("understanding".equals(multimodal.getString("task")));
        check(multimodal.getJSONObject("parameters").length() == 0);
        JSONObject specialized = CapabilityInvokeProvider.unitOptions(profile("SpecializedDetection", "specialized-detection",
                "{\"profile\":\"uav-fire-smoke\",\"confidence\":0.3}"));
        check("uav-fire-smoke".equals(specialized.getString("profile")));
        check(!specialized.has("parameters"));
        System.out.println("OmniOptionsTest: 9 assertions passed");
    }

    private static JSONObject profile(String unit, String adapter, String options) {
        return new JSONObject().put("unit", unit).put("adapter", adapter).put("optionsJson", options);
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Harness Omni options do not match the Unit contract");
    }
}
