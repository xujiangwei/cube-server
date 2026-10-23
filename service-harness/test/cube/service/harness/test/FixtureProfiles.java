package cube.service.harness.test;

import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import cube.service.harness.store.FileStore;
import org.json.*;
import java.nio.file.*;
import java.util.*;

/** Test configuration builder. Model/executor definitions stay in memory/files, never in tenant tables. */
final class FixtureProfiles {
    private final CatalogStore catalog;
    private FileStore files;
    private final Map<String, JSONObject> definitions = new HashMap<>(), bindings = new HashMap<>();

    FixtureProfiles(CatalogStore catalog) { this.catalog = catalog; }
    FixtureProfiles files(FileStore value) { files = value; return this; }
    private String key(String tenant, String kind, String id, String version) { return tenant + "/" + kind + "/" + id + "@" + version; }

    JSONObject publish(String tenant, JSONObject definition) {
        String kind = definition.getString("kind");
        definition = RuntimeSupport.copy(definition);
        if (kind.equals("runtime") && !definition.has("toolSetVersion")) definition.put("toolSetVersion", definition.getString("mode").equals("resources-only") ? "resource-tools-v1" : "broker-tools");
        if (kind.equals("policy")) return new ProfileRegistry(catalog).publish(tenant, definition);
        if (kind.equals("task")) {
            JSONObject execution = RuntimeSupport.copy(definition).put("kind", "execution").put("schemaVersion", 1);
            execution.remove("permissions"); execution.remove("skillReleases"); execution.remove("toolSetVersion");
            RuntimeSupport.validate("execution-profile", execution);
            RuntimeSupport.strings(definition.getJSONArray("permissions"));
            RuntimeSupport.strings(definition.getJSONArray("skillReleases"));
        } else RuntimeSupport.validate(kind + "-profile", definition);
        String key = key(tenant, kind, definition.getString("id"), definition.getString("version"));
        JSONObject old = definitions.get(key);
        if (old != null && !JdbcRunStore.hash(old).equals(JdbcRunStore.hash(definition))) throw new StoreException("IMMUTABLE_PROFILE_CONFLICT");
        definitions.put(key, RuntimeSupport.copy(definition));
        return RuntimeSupport.copy(definition);
    }

    JSONObject profile(String tenant, String kind, String id, String version) {
        JSONObject value = definitions.get(key(tenant, kind, id, version));
        if (value == null) throw new StoreException("PROFILE_NOT_FOUND");
        return RuntimeSupport.copy(value);
    }

    long bindingVersion(String tenant, String name) {
        JSONObject value = bindings.get(tenant + "/" + name);
        return value == null ? 0 : value.getLong("version");
    }

    void bind(String tenant, String kind, String name, String target, long expected) {
        if (!kind.equals("task")) throw new IllegalArgumentException("Fixture task binding required");
        if (bindingVersion(tenant, name) != expected) throw new StoreException("VERSION_CONFLICT");
        String[] ref = target.split("@", -1);
        profile(tenant, kind, ref[0], ref[1]);
        bindings.put(tenant + "/" + name, new JSONObject().put("target", target).put("version", expected + 1));
    }

    JSONObject createRun(ProfileRegistry.Identity identity, String key, String fixture, JSONObject request) {
        try {
            String[] ref = bindings.get(identity.tenant + "/" + fixture).getString("target").split("@", -1);
            JSONObject task = profile(identity.tenant, "task", ref[0], ref[1]);
            Properties p = new Properties();
            configure(p, identity.tenant, task, Files.createTempDirectory("harness-test-execution-"));
            return new ProfileRegistry(catalog, files, ExecutionProfiles.load(p)).createRun(identity, key, request);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    void configure(Properties properties, String tenant, JSONObject task, Path directory) throws Exception {
        Files.createDirectories(directory);
        for (String kind : Arrays.asList("model", "runtime")) {
            JSONObject ref = task.getJSONObject(kind);
            JSONObject definition = profile(tenant, kind, ref.getString("id"), ref.getString("version"));
            if (kind.equals("runtime") && task.has("toolSetVersion")) definition.put("toolSetVersion", task.getString("toolSetVersion"));
            Files.write(directory.resolve(kind + ".json"), RuntimeSupport.bytes(definition));
        }
        JSONObject execution = RuntimeSupport.copy(task).put("kind", "execution").put("id", "server-default").put("schemaVersion", 1);
        execution.remove("permissions"); execution.remove("skillReleases"); execution.remove("toolSetVersion");
        Files.write(directory.resolve("execution.json"), RuntimeSupport.bytes(execution));
        properties.setProperty("execution.profiles", directory.toString());
        properties.remove("execution.model"); properties.remove("execution.runtime");
        JSONObject policy = new JSONObject().put("schemaVersion", 1).put("kind", "policy").put("id", "default")
                .put("permissions", task.getJSONArray("permissions")).put("skillReleases", task.getJSONArray("skillReleases"))
                .put("businessLimits", new JSONObject().put("maxActiveRuns", 256).put("maxLifetimeMillis", 86400000));
        String version = JdbcRunStore.hash(policy);
        policy.put("version", version);
        new ProfileRegistry(catalog).publish(tenant, policy);
        long binding = 0;
        try { binding = catalog.active(tenant, "policy", "default").getLong("version"); }
        catch (StoreException e) { if (!e.code.equals("BINDING_NOT_FOUND")) throw e; }
        catalog.bind(tenant, "policy", "default", "default@" + version, binding);
    }

    JSONObject pinned(ProfileRegistry.Identity identity, String run) { return catalog.pinned(identity.tenant, identity.subject, run); }
}
