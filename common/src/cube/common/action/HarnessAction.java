package cube.common.action;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dispatcher/service business protocol. Independent of the Python worker protocol. */
public enum HarnessAction {
    Capabilities("GET", "capabilities", null),
    TaskProfiles("GET", "task-profiles", null),
    TaskProfile("GET", "task-profiles/ID", null),
    CreateRun("POST", "runs", "create-run"),
    ReadRun("GET", "runs/ID", null),
    Events("GET", "runs/ID/events", null),
    CancelRun("POST", "runs/ID/cancel", "cancel-run"),
    ResumeRun("POST", "runs/ID/resume", "resume-run"),
    Feedback("POST", "runs/ID/feedback", "feedback"),
    DecideApproval("POST", "runs/ID/approvals/ID/decision", "approval"),
    CreateInput("POST", "inputs", "input-create"),
    ReadInput("GET", "inputs/ID", null),
    UploadInput("PUT", "inputs/ID/content", null),
    CompleteInput("POST", "inputs/ID/complete", "input-complete"),
    Artifacts("GET", "runs/ID/artifacts", null),
    ReadArtifact("GET", "artifacts/ID", null),
    DownloadArtifact("GET", "artifacts/ID/content", null),
    DownloadHandle("GET", "artifacts/ID/download-handle", null),
    Trace("GET", "runs/ID/trace", null),
    StartDownload(null, null, null),
    TransferResult(null, null, null),
    AbortTransfer(null, null, null);

    public static final String CELLET = "Harness";
    public static final int MAX_BODY_BYTES = 262144;
    public static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    public final String name, method, schema;
    private final Pattern path;

    HarnessAction(String method, String path, String schema) {
        this.name = "harness." + name();
        this.method = method;
        this.schema = schema;
        this.path = path == null ? null : Pattern.compile("/harness/v1/" + path.replace("ID", "([A-Za-z0-9_.:-]+)"));
    }

    public Matcher match(String path) {
        return this.path == null ? null : this.path.matcher(path);
    }

    public static HarnessAction fromName(String name) {
        for (HarnessAction action : values()) if (action.name.equals(name)) return action;
        return null;
    }
}
