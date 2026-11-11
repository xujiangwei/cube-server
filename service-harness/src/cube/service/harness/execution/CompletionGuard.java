package cube.service.harness.execution;

import org.json.JSONArray;
import org.json.JSONObject;

/** Deployment-installed business completion check. Uploaded resources cannot install Java code. */
public interface CompletionGuard extends AutoCloseable {
    String FILE = "workflow-requirements.json";
    String REPORT_FILE = "report-requirements.json";

    interface Resources {
        JSONObject json(String path) throws Exception;
        default byte[] bytes(String path) throws Exception { throw new UnsupportedOperationException("RESOURCE_BYTES_UNAVAILABLE"); }
    }

    void validate(Resources resources) throws Exception;

    /** Evidence is supplied exclusively by the host's confirmed Provider/job ledger. */
    JSONObject check(Resources resources, JSONArray evidence) throws Exception;

    default boolean supportsReports() { return false; }
    default void validateReport(Resources resources) throws Exception { throw new IllegalStateException("REPORT_GUARD_UNAVAILABLE"); }
    default JSONObject checkFinal(Resources resources, JSONArray evidence, JSONObject decision,
                                  String skill, java.util.Set<String> reportSkills) throws Exception {
        return check(resources, evidence);
    }

    default void close() { }
}
