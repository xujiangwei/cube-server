package cube.service.harness.skill;

import cube.service.harness.execution.FileProcessingProvider;
import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Installed operation families; package arguments never select a command or a filesystem path. */
public final class FileUtilityProvider extends AbstractProvider implements FileProcessingProvider {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final String family;
    private final JSONObject processing;

    public FileUtilityProvider(Properties config) throws Exception {
        this(family(config), config);
    }

    private static String family(Properties config) {
        String group = config.getProperty("family", "");
        if (!Arrays.asList("pdf", "image", "table", "report").contains(group))
            throw new StoreException("FILE_OPERATION_INVALID");
        return group;
    }

    private FileUtilityProvider(String family, Properties config) throws Exception {
        super(family, "common." + family + ".process", family + ".process", config);
        this.family = family;
        JSONObject spec = StrictJson.object(Files.readAllBytes(Paths.get(config.getProperty("environment.manifest"))));
        String operation = spec.getString("operation");
        String required = family + ".utility.v1";
        if (spec.getInt("schemaVersion") != 1 || (!operation.equals(required)
                && !(family.equals("pdf") && operation.equals("pdf.watermark.v1"))))
            throw new StoreException("FILE_ENVIRONMENT_INVALID");
        this.processing = new JSONObject().put("environmentId", RuntimeSupport.id(spec.getString("id")))
                .put("environmentDigest", JdbcRunStore.hash(spec)).put("operation", required);
    }

    public String effect() { return "artifact-create"; }
    public int runnerWaitMillis() { return 120000; }
    public JSONObject fileProcessing() { return new JSONObject(processing.toString()); }
    public String inputId(JSONObject args) { return args.getString("inputId"); }
    public List<String> inputIds(JSONObject args) {
        if (!args.has("inputIds")) return family.equals("report") ? Collections.emptyList()
                : Collections.singletonList(RuntimeSupport.id(inputId(args)));
        List<String> ids = new ArrayList<>();
        JSONArray list = args.getJSONArray("inputIds");
        for (int index = 0; index < list.length(); index++) ids.add(RuntimeSupport.id(list.getString(index)));
        if (new HashSet<>(ids).size() != ids.size()) throw new StoreException("FILE_INPUT_DUPLICATE");
        return ids;
    }
    public Set<String> inputMediaTypes() {
        if (family.equals("pdf")) return Collections.singleton("application/pdf");
        if (family.equals("table")) return new HashSet<>(Arrays.asList("text/csv", XLSX));
        return new HashSet<>(Arrays.asList("image/png", "image/jpeg"));
    }

    private String format(JSONObject args) {
        switch (family) {
            case "pdf":
                switch (args.getString("action")) {
                    case "text": return "txt";
                    case "split": return "zip";
                    default: return "pdf";
                }
            case "table": return args.optString("format", "csv");
            default: return args.getString("format");
        }
    }

    private static String mediaType(String format) {
        switch (format) {
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "zip": return "application/zip";
            case "jpg": return "image/jpeg";
            case "png": return "image/png";
            case "csv": return "text/csv";
            case "xlsx": return XLSX;
            case "docx": return DOCX;
            default: throw new StoreException("FILE_TYPE_UNSUPPORTED");
        }
    }

    @Override
    public void validate(JSONObject args) {
        super.validate(args);
        List<String> ids = inputIds(args);
        String format = format(args);
        String name = args.optString("fileName", family + "." + format);
        if (!name.toLowerCase(Locale.ROOT).endsWith("." + format) || !name.matches("[^\\\\/\\p{Cntrl}]{1,256}")
                || name.length() <= format.length() + 1)
            throw new StoreException("INVALID_FILE_METADATA");
        if (family.equals("pdf")) {
            String action = args.getString("action");
            if (action.equals("merge") ? !args.has("inputIds") || ids.size() < 2 || args.has("inputId") || args.has("pages")
                    : ids.size() != 1 || args.has("inputIds") || action.equals("extract") && !args.has("pages"))
                throw new StoreException("PDF_PARAMETERS_INVALID");
            JSONArray pages = args.optJSONArray("pages");
            Set<Integer> unique = new HashSet<>();
            if (pages != null) for (int i = 0; i < pages.length(); i++) {
                if (!unique.add(pages.getInt(i))) throw new StoreException("PDF_PAGE_RANGE_INVALID");
            }
        } else if (family.equals("image") && args.has("quality") && format.equals("png")) {
            throw new StoreException("IMAGE_PARAMETERS_INVALID");
        }
    }

    public JSONObject outputMetadata(JSONObject args, long size, String sha256) {
        validate(args);
        if (size < 1 || size > 10485760) throw new StoreException("FILE_TOO_LARGE");
        String format = format(args);
        return new JSONObject().put("fileName", args.optString("fileName", family + "." + format))
                .put("mediaType", mediaType(format)).put("size", size).put("sha256", RuntimeSupport.digest(sha256));
    }

    protected JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception {
        JSONObject result = new JSONObject().put("artifact", context.binaryArtifact())
                .put("operation", processing.getString("operation")).put("inputIds", new JSONArray(inputIds(args)));
        if (Arrays.asList("txt", "csv").contains(format(args)))
            result.put("preview", context.binaryArtifactText(1024));
        return result;
    }
}
