package cube.service.harness.skill;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded independent checks. Review flags never replace raw observations or physical receipts. */
final class ObservationReview {
    static JSONObject review(JSONObject profile, JSONObject analysis, String status) {
        JSONArray flags = new JSONArray();
        JSONObject output = new JSONObject().put("method", "observation-review-v1").put("modelCalls", 0)
                .put("reportedInputTokens", 0).put("reportedOutputTokens", 0).put("semanticallyVerified", false)
                .put("analysisResultSha256", RuntimeSupport.sha(RuntimeSupport.bytes(analysis)));
        JSONObject dimensions = new JSONObject().put("uniqueTargetCount", JSONObject.NULL)
                .put("businessAbnormality", status).put("countUnit", "unavailable");
        String answer = analysis.getString("answer");
        if (answer.matches("(?s).*(未经授权|非法进入|盗伐|犯罪|嫌疑人|工作人员|巡护员|身份确认|unauthori[sz]ed|criminal).*")) flags.put("IDENTITY_OR_AUTHORIZATION_INFERENCE");
        if (answer.matches("(?s).*(正在砍伐|正在盗|故意|正在作案|蓄意|intentional).*")) flags.put("BEHAVIOR_INFERENCE");
        if (Arrays.asList("normal", "abnormal").contains(status)) flags.put("BUSINESS_RULE_FACTS_REQUIRE_REVIEW");
        JSONObject options = ConnectorConfig.arguments(profile.getString("optionsJson"));
        if ("SpecializedDetection".equals(profile.getString("unit"))) {
            String route = options.getString("profile");
            output.put("route", new JSONObject().put("unit", profile.getString("unit")).put("profile", route)
                    .put("profileHash", RuntimeSupport.sha(RuntimeSupport.bytes(profile))));
            try {
                JSONObject payload = StrictJson.object(analysis.getString("payloadJson").getBytes(StandardCharsets.UTF_8));
                if (!route.equals(payload.getString("profile"))) throw new IllegalArgumentException();
                JSONArray refs = payload.getJSONArray("refs");
                List<JSONObject> boxes = new ArrayList<>(); List<String> classes = new ArrayList<>();
                Set<String> allowed = new HashSet<>();
                JSONArray configured = options.optJSONArray("classes");
                if (configured != null) for (int i = 0; i < configured.length(); i++) allowed.add(configured.getString(i));
                else allowed.addAll(Arrays.asList("fire", "smoke"));
                if (refs.length() > 64) throw new IllegalArgumentException();
                JSONObject byClass = new JSONObject();
                double width = payload.optDouble("width", Double.NaN), height = payload.optDouble("height", Double.NaN);
                for (int r = 0; r < refs.length(); r++) {
                    JSONObject ref = refs.getJSONObject(r); String category = ref.getString("content");
                    JSONArray found = ref.getJSONArray("boxes");
                    if (!allowed.contains(category) || boxes.size() + found.length() > 256) throw new IllegalArgumentException();
                    for (int b = 0; b < found.length(); b++) {
                        JSONObject box = found.getJSONObject(b);
                        for (String key : Arrays.asList("x1", "y1", "x2", "y2", "confidence"))
                            if (!(box.get(key) instanceof Number) || !Double.isFinite(box.getDouble(key))) throw new IllegalArgumentException();
                        if (box.getDouble("x1") < 0 || box.getDouble("y1") < 0 || box.getDouble("x2") <= box.getDouble("x1")
                                || box.getDouble("y2") <= box.getDouble("y1") || box.getDouble("confidence") < 0 || box.getDouble("confidence") > 1
                                || Double.isFinite(width) && box.getDouble("x2") > width || Double.isFinite(height) && box.getDouble("y2") > height) throw new IllegalArgumentException();
                        boxes.add(box); classes.add(category);
                        if (Double.isFinite(width) && Double.isFinite(height) && width > 0 && height > 0
                                && (box.getDouble("x2") - box.getDouble("x1")) * (box.getDouble("y2") - box.getDouble("y1")) / (width * height) < 0.001)
                            if (!flags.toList().contains("SMALL_TARGET_REVIEW")) flags.put("SMALL_TARGET_REVIEW");
                    }
                    byClass.put(category, byClass.optInt(category) + found.length());
                }
                JSONArray overlaps = new JSONArray();
                for (int a = 0; a < boxes.size(); a++) for (int b = a + 1; b < boxes.size() && overlaps.length() < 16; b++) {
                    if (!classes.get(a).equals(classes.get(b))) continue;
                    double iou = iou(boxes.get(a), boxes.get(b));
                    if (iou > 0) overlaps.put(new JSONObject().put("boxIndices", new JSONArray().put(a).put(b)).put("class", classes.get(a)).put("iou", iou));
                }
                if (overlaps.length() > 0) flags.put("OVERLAPPING_BOXES_REQUIRE_IDENTITY_REVIEW");
                dimensions.put("presence", !boxes.isEmpty()).put("boxCount", boxes.size()).put("boxesByClass", byClass)
                        .put("countUnit", "boxes-per-image").put("scorable", true).put("overlapCandidates", overlaps)
                        .put("deduplication", "none; overlap alone does not establish identity");
                if ("fire-smoke".equals(route)) flags.put("SMOKE_CLOUD_AND_CONTROLLED_BURN_REVIEW");
            } catch (Exception invalid) {
                dimensions = new JSONObject().put("presence", JSONObject.NULL).put("boxCount", JSONObject.NULL)
                        .put("uniqueTargetCount", JSONObject.NULL).put("countUnit", "unavailable").put("scorable", false).put("businessAbnormality", status);
                flags.put("DETECTION_PAYLOAD_UNSCORABLE");
            }
        } else {
            dimensions.put("countUnit", "visible-scene");
            flags.put("PASSAGE_AND_ROADSIDE_INTERPRETATION_REVIEW");
        }
        return output.put("dimensions", dimensions).put("flags", flags)
                .put("status", flags.length() == 0 ? "program-checks-passed" : "review-required");
    }

    static double iou(JSONObject a, JSONObject b) {
        double intersection = Math.max(0, Math.min(a.getDouble("x2"), b.getDouble("x2")) - Math.max(a.getDouble("x1"), b.getDouble("x1")))
                * Math.max(0, Math.min(a.getDouble("y2"), b.getDouble("y2")) - Math.max(a.getDouble("y1"), b.getDouble("y1")));
        double areaA = (a.getDouble("x2") - a.getDouble("x1")) * (a.getDouble("y2") - a.getDouble("y1"));
        double areaB = (b.getDouble("x2") - b.getDouble("x1")) * (b.getDouble("y2") - b.getDouble("y1"));
        return intersection / (areaA + areaB - intersection);
    }
}
