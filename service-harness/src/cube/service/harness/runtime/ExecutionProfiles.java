package cube.service.harness.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Server-owned execution configuration. No definition belongs to a tenant. */
public final class ExecutionProfiles {
    private final JSONObject execution;
    private final Map<String, JSONObject> definitions = new TreeMap<>();
    private final Map<String, String> current = new TreeMap<>();

    private ExecutionProfiles(JSONObject execution, Map<String, JSONObject> definitions) {
        this.execution = RuntimeSupport.copy(execution);
        for (Map.Entry<String, JSONObject> entry : definitions.entrySet()) this.definitions.put(entry.getKey(), RuntimeSupport.copy(entry.getValue()));
        JSONObject active = execution.optJSONObject("activeVersions");
        for (String kind : Arrays.asList("model", "runtime")) {
            Map<String, List<String>> versions = new TreeMap<>();
            for (JSONObject value : list(kind)) versions.computeIfAbsent(value.getString("id"), k -> new ArrayList<>()).add(value.getString("version"));
            JSONObject selected = active == null ? null : active.optJSONObject(kind);
            if (selected != null) for (String id : selected.keySet()) resolve(kind, id + "@" + selected.getString(id));
            for (Map.Entry<String, List<String>> entry : versions.entrySet()) {
                String version = selected == null ? null : selected.optString(entry.getKey(), null);
                if (version == null) {
                    if (entry.getValue().size() != 1) throw RuntimeSupport.error("ACTIVE_VERSION_REQUIRED");
                    version = entry.getValue().get(0);
                }
                current.put(kind + ":" + entry.getKey(), version);
            }
        }
        select(new JSONObject());
    }

    public static ExecutionProfiles load(Properties properties) throws IOException {
        Path directory = Paths.get(properties.getProperty("execution.profiles", "./config/harness-execution"));
        Map<String, JSONObject> definitions = new HashMap<>();
        JSONObject execution = null;
        try (Stream<Path> paths = Files.list(directory)) {
            Iterator<Path> entries = paths.sorted().iterator();
            while (entries.hasNext()) {
                Path path = entries.next();
                if (!path.getFileName().toString().endsWith(".json")) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid execution file: " + path);
                JSONObject value;
                try (java.io.InputStream input = Files.newInputStream(path)) {
                    value = cube.service.harness.protocol.StrictJson.object(RuntimeSupport.read(input, 1048576));
                }
                String kind = value.getString("kind");
                if (kind.equals("execution")) {
                    RuntimeSupport.validate("execution-profile", value);
                    if (execution != null) throw new IOException("Multiple execution definitions: " + directory);
                    execution = value;
                } else {
                    if (!Arrays.asList("model", "runtime").contains(kind)) throw new IOException("Not a server execution profile: " + path);
                    ProfileRegistry.validateDefinition(value);
                    String key = kind + ":" + value.getString("id") + "@" + value.getString("version");
                    if (definitions.put(key, value) != null) throw new IOException("Duplicate execution profile: " + key);
                }
                if (definitions.size() > 256) throw new IOException("Too many execution profiles");
            }
            if (execution == null) throw new IOException("Missing execution definition: " + directory);
            for (String kind : Arrays.asList("model", "runtime")) {
                String override = properties.getProperty("execution." + kind);
                if (override != null) {
                    String[] ref = override.split("@", -1);
                    if (ref.length != 2) throw new IOException("execution." + kind + " requires id@version");
                    execution.put(kind, new JSONObject().put("id", RuntimeSupport.id(ref[0])).put("version", RuntimeSupport.id(ref[1])));
                }
            }
            return new ExecutionProfiles(execution, definitions);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid server execution configuration: " + directory, e); }
    }

    public static JSONObject ref(JSONObject value) {
        return new JSONObject().put("id", value.getString("id")).put("version", value.getString("version"));
    }

    public List<JSONObject> list(String kind) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject value : definitions.values()) if (value.getString("kind").equals(kind)) out.add(RuntimeSupport.copy(value));
        return out;
    }

    public JSONObject resolve(String kind, String selector) {
        String[] parts = selector.split("@", -1);
        if (parts.length > 2) throw RuntimeSupport.error("INVALID_PROFILE_REF");
        String id = RuntimeSupport.id(parts[0]);
        String version = parts.length == 2 ? RuntimeSupport.id(parts[1]) : current.get(kind + ":" + id);
        JSONObject value = definitions.get(kind + ":" + id + "@" + version);
        if (value == null) throw RuntimeSupport.error(kind.toUpperCase(Locale.ROOT) + "_NOT_FOUND");
        return RuntimeSupport.copy(value);
    }

    public JSONObject select(JSONObject request) {
        JSONObject selected = execution();
        JSONObject model = request.has("model") ? resolve("model", request.getString("model")) : model();
        JSONObject runtime = request.has("runtime") ? resolve("runtime", request.getString("runtime")) : runtime();
        Set<String> capabilities = RuntimeSupport.strings(model.getJSONArray("capabilities"));
        capabilities.addAll(RuntimeSupport.strings(runtime.getJSONArray("capabilities")));
        if (!capabilities.containsAll(RuntimeSupport.strings(selected.getJSONArray("requiredCapabilities")))) throw RuntimeSupport.error("EXECUTION_CAPABILITY_MISMATCH");
        selected.put("model", ref(model)).put("runtime", ref(runtime)).put("toolSetVersion", runtime.getString("toolSetVersion"));
        selected.remove("activeVersions");
        return new JSONObject().put("execution", selected).put("model", model).put("runtime", runtime);
    }

    public JSONObject defaults() { return new JSONObject().put("model", ref(model())).put("runtime", ref(runtime())); }
    public boolean current(JSONObject value) { return value.getString("version").equals(current.get(value.getString("kind") + ":" + value.getString("id"))); }
    public JSONObject execution() { return RuntimeSupport.copy(execution); }
    public JSONObject model() { JSONObject ref = execution.getJSONObject("model"); return resolve("model", ref.getString("id") + "@" + ref.getString("version")); }
    public JSONObject runtime() { JSONObject ref = execution.getJSONObject("runtime"); return resolve("runtime", ref.getString("id") + "@" + ref.getString("version")); }

    /** Read the server configuration pinned when the run was created. */
    public static JSONObject execution(JSONObject pin) {
        return pin.getJSONObject("execution");
    }
}
