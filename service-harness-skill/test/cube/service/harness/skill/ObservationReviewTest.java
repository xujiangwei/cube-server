package cube.service.harness.skill;

import org.json.*;
import java.nio.file.*;

/** Frozen two-person/three-box evidence; overlap never becomes an identity threshold. */
public final class ObservationReviewTest {
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        JSONObject raw = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8"));
        String before = raw.toString();
        JSONObject profile = new JSONObject().put("unit", "SpecializedDetection")
                .put("optionsJson", "{\"profile\":\"thing\",\"classes\":[\"person\"]}");
        JSONObject analysis = new JSONObject().put("answer", "").put("payloadJson", raw.getJSONObject("resultPayload").toString());
        JSONObject review = ObservationReview.review(profile, analysis, "not-assessed");
        JSONObject dimensions = review.getJSONObject("dimensions");
        check(dimensions.getBoolean("presence") && dimensions.getInt("boxCount") == 3 && dimensions.isNull("uniqueTargetCount"), "presence, boxes and unique count have distinct dimensions");
        JSONArray overlaps = dimensions.getJSONArray("overlapCandidates"); double best = 0;
        for (int i = 0; i < overlaps.length(); i++) best = Math.max(best, overlaps.getJSONObject(i).getDouble("iou"));
        check(Math.abs(best - 0.6733376854463761) < 0.000001, "known overlapping pair reproduced from raw coordinates");
        check(raw.toString().equals(before) && !review.getBoolean("semanticallyVerified") && review.getInt("modelCalls") == 0, "raw payload intact and bounded program review cost explicit");
        JSONObject malformed = new JSONObject(raw.getJSONObject("resultPayload").toString());
        malformed.getJSONArray("refs").getJSONObject(0).getJSONArray("boxes").getJSONObject(0).put("confidence", "0.9");
        analysis.put("payloadJson", malformed.toString());
        check(!ObservationReview.review(profile, analysis, "not-assessed").getJSONObject("dimensions").getBoolean("scorable"), "malformed result cannot become an empty detection");
        analysis.put("payloadJson", before).put("answer", "工作人员未经授权正在砍伐");
        check(ObservationReview.review(profile, analysis, "abnormal").getJSONArray("flags").toString().contains("IDENTITY_OR_AUTHORIZATION_INFERENCE"), "unsupported identity and authorization claims require review");
        System.out.println("PASS observation review: raw 3 boxes, unique count unknown, exact overlap evidence, malformed payload, unsupported inference and zero added model cost.");
    }
}
