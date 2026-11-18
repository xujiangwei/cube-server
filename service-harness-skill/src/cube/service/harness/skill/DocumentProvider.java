package cube.service.harness.skill;

import cube.service.harness.execution.FileProcessingProvider;
import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

/** PDF edits belong to an installed Python worker. This provider only authorizes files and results. */
public final class DocumentProvider extends AbstractProvider implements FileProcessingProvider {
    private final JSONObject processing;
    public DocumentProvider(Properties config) throws Exception {
        super("document", "common.document.watermark", "document.watermark", config);
        JSONObject environment = StrictJson.object(Files.readAllBytes(Paths.get(config.getProperty("environment.manifest"))));
        if (environment.getInt("schemaVersion") != 1 || !environment.getString("operation").equals("pdf.watermark.v1"))
            throw new StoreException("FILE_ENVIRONMENT_INVALID");
        processing = new JSONObject().put("environmentId", RuntimeSupport.id(environment.getString("id")))
                .put("environmentDigest", JdbcRunStore.hash(environment)).put("operation", "pdf.watermark.v1");
    }
    public String effect() { return "artifact-create"; }
    public int runnerWaitMillis() { return 120000; }
    public JSONObject fileProcessing() { return new JSONObject(processing.toString()); }
    public String inputId(JSONObject arguments) { return arguments.getString("inputId"); }
    public Set<String> inputMediaTypes() { return Collections.singleton("application/pdf"); }
    public JSONObject outputMetadata(JSONObject arguments, long size, String sha256) {
        validate(arguments);
        if (size < 1 || size > 10485760) throw new StoreException("FILE_TOO_LARGE");
        return new JSONObject().put("fileName", arguments.optString("fileName", "watermarked.pdf"))
                .put("mediaType", "application/pdf").put("size", size).put("sha256", RuntimeSupport.digest(sha256));
    }
    public void validate(JSONObject arguments) {
        super.validate(arguments);
        if (arguments.getString("text").trim().isEmpty() || arguments.getString("text").matches("(?s).*[\\p{Cntrl}].*"))
            throw new StoreException("WATERMARK_TEXT_INVALID");
        String name = arguments.optString("fileName", "watermarked.pdf");
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") || !name.matches("[^\\\\/\\p{Cntrl}]{5,256}"))
            throw new StoreException("INVALID_FILE_METADATA");
        if (arguments.has("pages")) {
            java.util.Set<Integer> pages = new java.util.HashSet<>();
            for (int i = 0; i < arguments.getJSONArray("pages").length(); i++)
                if (!pages.add(arguments.getJSONArray("pages").getInt(i))) throw new StoreException("PDF_PAGE_RANGE_INVALID");
        }
    }
    protected JSONObject execute(ProviderInvocation context, JSONObject arguments) {
        return new JSONObject().put("artifact", context.binaryArtifact()).put("operation", "pdf.watermark.v1")
                .put("inputId", inputId(arguments));
    }
}
