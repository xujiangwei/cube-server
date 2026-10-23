package cube.service.harness.runtime;

import cube.service.harness.protocol.StrictJson;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Tenant defaults contain business policy only; execution definitions are rejected. */
public final class TenantTemplates {
    private final String definition;
    public final String digest;

    private TenantTemplates(JSONObject policy) {
        definition = cube.service.harness.store.JdbcRunStore.canonical(policy);
        digest = RuntimeSupport.sha(RuntimeSupport.bytes(policy));
    }

    public static TenantTemplates load(Path directory) throws IOException {
        JSONObject policy = null;
        try (Stream<Path> paths = Files.list(directory)) {
            Iterator<Path> entries = paths.sorted().iterator();
            while (entries.hasNext()) {
                Path path = entries.next();
                if (!path.getFileName().toString().endsWith(".json")) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid tenant policy file: " + path);
                JSONObject value;
                try (java.io.InputStream input = Files.newInputStream(path)) {
                    value = StrictJson.object(RuntimeSupport.read(input, 1048576));
                }
                RuntimeSupport.validate("tenant-policy", value);
                RuntimeSupport.strings(value.getJSONArray("permissions"));
                RuntimeSupport.strings(value.getJSONArray("skillReleases"));
                if (!value.getString("id").equals("default") || value.getJSONArray("skillReleases").length() != 0) {
                    throw new IOException("Tenant default policy must use id=default and no skill releases");
                }
                if (policy != null) throw new IOException("Exactly one default tenant policy is required");
                policy = value;
            }
            if (policy == null) throw new IOException("Missing default tenant policy: " + directory);
            return new TenantTemplates(policy);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid tenant policy configuration: " + directory, e); }
    }

    public List<JSONObject> profiles() { return Collections.singletonList(new JSONObject(definition)); }
}
