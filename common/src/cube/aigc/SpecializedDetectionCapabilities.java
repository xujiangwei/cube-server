package cube.aigc;

import cube.common.entity.AICapability;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Detection routing metadata derived from the existing ordered subtasks. */
public final class SpecializedDetectionCapabilities {
    private SpecializedDetectionCapabilities() { }

    public static List<String> profiles(AICapability capability) {
        List<String> tasks = capability.getSubtasks();
        if (!"SpecializedDetection".equals(capability.getName()) || tasks.size() < 2
                || !"ObjectDetection".equals(tasks.get(0))) return Collections.emptyList();
        Set<String> names = new LinkedHashSet<>();
        for (String task : tasks.subList(1, tasks.size())) {
            if (task == null || task.trim().isEmpty() || "ObjectDetection".equals(task)
                    || !names.add(task)) return Collections.emptyList();
        }
        return new ArrayList<>(names);
    }

    public static boolean contains(JSONObject descriptor, String field, String name) {
        JSONArray values = descriptor.optJSONArray(field);
        if (values != null) for (int i = 0; i < values.length(); i++)
            if (name.equals(values.opt(i))) return true;
        return false;
    }

    public static boolean supports(JSONObject descriptor, String profile, boolean sliced) {
        return contains(descriptor, "profiles", profile)
                && (!sliced || contains(descriptor, "slicedProfiles", profile));
    }

    public static boolean ready(JSONObject descriptor) {
        return descriptor.optBoolean("ready", false)
                && descriptor.optLong("statusExpiresAt", 0) > System.currentTimeMillis();
    }
}
