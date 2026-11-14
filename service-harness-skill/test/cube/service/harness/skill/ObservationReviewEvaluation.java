package cube.service.harness.skill;

import org.json.*;
import java.nio.file.*;

/** Offline diagnostic executes the same bounded review used by normalized reports. */
public final class ObservationReviewEvaluation {
    public static void main(String[] args) throws Exception {
        JSONArray input = new JSONArray(new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8"));
        JSONArray output = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject row = input.getJSONObject(i);
            output.put(new JSONObject().put("identity", row.getString("identity"))
                    .put("review", ObservationReview.review(row.getJSONObject("profile"), row.getJSONObject("analysis"), "not-assessed")));
        }
        Files.write(Paths.get(args[1]), output.toString(2).getBytes("UTF-8"));
    }
}
