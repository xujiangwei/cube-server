package cube.service.harness.runtime;

import cube.service.harness.store.CatalogStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.sql.Connection;
import java.util.*;

/** Resolves tenant business policy and server execution configuration into an immutable run. */
public final class ProfileRegistry {
    public static final Set<String> PLATFORM_PERMISSIONS = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
            "skill.list", "skill.activate", "resource.list", "resource.read", "resource.search",
            "input.read_text", "artifact.write_text")));
    private final CatalogStore catalog;
    private final cube.service.harness.store.FileStore files;
    private final ExecutionProfiles deployment;

    public ProfileRegistry(CatalogStore catalog) { this(catalog, null, null); }
    public ProfileRegistry(CatalogStore catalog, cube.service.harness.store.FileStore files) { this(catalog, files, null); }
    public ProfileRegistry(CatalogStore catalog, cube.service.harness.store.FileStore files, ExecutionProfiles deployment) {
        this.catalog = catalog;
        this.files = files;
        this.deployment = deployment;
    }

    /** Trusted identity; never constructed from a client request body. */
    public static final class Identity {
        public final String tenant, subject;
        public final Set<String> permissions;
        public Identity(String tenant, String subject, Set<String> permissions) {
            this.tenant = RuntimeSupport.id(tenant);
            this.subject = RuntimeSupport.id(subject);
            this.permissions = Collections.unmodifiableSet(new TreeSet<>(permissions));
        }
    }

    public JSONObject publish(String tenant, JSONObject definition) {
        String kind = definition.getString("kind");
        if (!kind.equals("policy")) throw RuntimeSupport.error("EXECUTION_CONFIGURATION_IS_SERVER_MANAGED");
        validateDefinition(definition);
        if (definition.has("skillReleases")) {
            for (String digest : RuntimeSupport.strings(definition.getJSONArray("skillReleases"))) catalog.release(tenant, digest);
        }
        return catalog.putProfile(tenant, RuntimeSupport.copy(definition));
    }

    public static void validateDefinition(JSONObject definition) {
        String kind = definition.getString("kind");
        if (!Arrays.asList("model", "runtime", "policy").contains(kind)) throw RuntimeSupport.error("UNKNOWN_PROFILE_KIND");
        RuntimeSupport.validate(kind.equals("policy") ? "tenant-policy" : kind + "-profile", definition);
        for (String key : Arrays.asList("capabilities", "skillReleases", "permissions", "requiredCapabilities")) {
            if (definition.has(key)) RuntimeSupport.strings(definition.getJSONArray(key));
        }
    }

    /** Resolve only inside the creation transaction, after durable idempotent replay. */
    public JSONObject createRun(Identity identity, String key, JSONObject request) {
        return createRun(identity, key, request, pin -> { });
    }

    public JSONObject createRun(Identity identity, String key, JSONObject request, java.util.function.Consumer<JSONObject> admission) {
        if (deployment == null) throw RuntimeSupport.error("EXECUTION_CONFIGURATION_REQUIRED");
        cube.service.harness.protocol.Contract.parse("create-run", RuntimeSupport.bytes(request));
        // The idempotency namespace is stable across server configuration changes.
        return catalog.createRun(identity.tenant, identity.subject, key, "server-default", request, c -> {
            validateAttachments(request);
            bindAttachments(c, identity, request);
            JSONObject policy = catalog.tenantPolicy(c, identity.tenant);
            RuntimeSupport.validate("tenant-policy", policy);
            JSONObject business = policy.getJSONObject("businessLimits");
            catalog.checkAdmission(c, identity.tenant, business.getInt("maxActiveRuns"));
            JSONObject pin = deployment.select(request), execution = pin.getJSONObject("execution");
            JSONObject limits = execution.getJSONObject("executionLimits");
            limits.put("maxLifetimeMillis", Math.min(limits.getLong("maxLifetimeMillis"), business.getLong("maxLifetimeMillis")));
            RunSelection resolver = new RunSelection(catalog, files != null);
            resolver.resolve(identity, policy, resolver.releases(c, identity, policy), request, pin);
            admission.accept(pin);
            return pin;
        });
    }

    /** Authenticated capability directory. Never exports private backend configuration. */
    public JSONObject describe(Identity identity) {
        try {
            JSONObject policy = catalog.tenantPolicy(identity.tenant);
            RunSelection resolver = new RunSelection(catalog, files != null);
            JSONArray releases = resolver.releases(null, identity, policy);
            Set<String> granted = RunSelection.permissions(identity, policy, releases);
            JSONArray models = new JSONArray(), runtimes = new JSONArray(), skills = new JSONArray();
            for (JSONObject model : deployment.list("model")) models.put(ExecutionProfiles.ref(model)
                    .put("capabilities", model.getJSONArray("capabilities")).put("nativeTools", model.optBoolean("nativeTools", false)).put("current", deployment.current(model)));
            for (JSONObject runtime : deployment.list("runtime")) runtimes.put(ExecutionProfiles.ref(runtime)
                    .put("mode", runtime.getString("mode")).put("capabilities", runtime.getJSONArray("capabilities")).put("current", deployment.current(runtime)));
            if (granted.contains("skill.list")) for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i), manifest = release.getJSONObject("manifest");
                skills.put(new JSONObject().put("name", manifest.getString("name")).put("version", manifest.getString("version"))
                        .put("digest", release.getString("digest")).put("dependencies", release.getJSONArray("dependencies"))
                        .put("compatibleRuntimes", compatibleRuntimes(release, releases)));
            }
            JSONArray tools = resolver.tools(granted, granted.contains("skill.list") ? releases : new JSONArray());
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                JSONObject release = tool.has("release") ? CatalogStore.requireRelease(new JSONObject().put("releases", releases), tool.getString("release")) : null;
                tool.put("compatibleRuntimes", compatibleRuntimes(release, releases));
            }
            return new JSONObject().put("defaults", deployment.defaults()).put("models", models).put("runtimes", runtimes).put("skills", skills).put("tools", tools);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Capability catalog could not be read", e); }
    }

    private JSONArray compatibleRuntimes(JSONObject release, JSONArray releases) {
        JSONArray out = new JSONArray();
        for (JSONObject runtime : deployment.list("runtime")) {
            try {
                if (release == null || RunSelection.compatibleWithDependencies(release, releases, runtime)) out.put(ExecutionProfiles.ref(runtime));
            } catch (cube.service.harness.store.StoreException e) {
                if (!e.code.equals("DEPENDENCY_NOT_PINNED")) throw e;
            }
        }
        return out;
    }

    private void validateAttachments(JSONObject request) {
        if (files == null && request.getJSONObject("input").getJSONArray("attachmentIds").length() != 0) {
            throw RuntimeSupport.error("ATTACHMENTS_NOT_IMPLEMENTED");
        }
    }

    private void bindAttachments(Connection c, Identity identity, JSONObject request) throws Exception {
        if (files != null) files.validateBindings(c, identity.tenant, identity.subject,
                request.getJSONObject("input").getJSONArray("attachmentIds"));
    }

    public JSONObject pinned(Identity identity, String runId) { return catalog.pinned(identity.tenant, identity.subject, runId); }
}
