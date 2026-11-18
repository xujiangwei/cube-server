package cube.service.harness.execution;

import org.json.JSONObject;

/** Trusted, installed Python operation. Package scripts never receive its interpreter or libraries. */
public interface FileProcessingProvider extends QueryProvider {
    JSONObject fileProcessing();
    String inputId(JSONObject arguments);
    java.util.Set<String> inputMediaTypes();
    default java.util.List<String> inputIds(JSONObject arguments) {
        return java.util.Collections.singletonList(inputId(arguments));
    }
    default java.util.Set<String> inputMediaTypes(JSONObject arguments, int index) {
        return inputMediaTypes();
    }
    JSONObject outputMetadata(JSONObject arguments, long size, String sha256);
}
