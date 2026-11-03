package cube.service.harness.runtime;

import cube.common.action.HarnessAction;
import cube.service.harness.execution.ToolBroker;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Remote facade over the same package/policy/CAS services used by RuntimeAdmin. */
public final class RemoteAdmin {
    private final CatalogStore catalog;
    private final PackageRepository repository;
    private final ProviderStore metadata;
    private final ProfileRegistry profiles;
    private final ToolBroker broker;
    private final Properties config;
    private final Set<String> permissions;
    public RemoteAdmin(CatalogStore catalog, PackageRepository repository, ProviderStore metadata,
            ProfileRegistry profiles, ToolBroker broker, Properties config, Set<String> permissions) {
        this.catalog = catalog; this.repository = repository; this.metadata = metadata;
        this.profiles = profiles; this.broker = broker; this.config = config; this.permissions = permissions;
    }
    public static Map<String,java.security.PublicKey> signers(Properties p) throws Exception {
        Map<String,java.security.PublicKey> keys = new HashMap<>();
        for (String key : p.stringPropertyNames()) if (key.startsWith("runtime.signer.")) keys.put(key.substring(15),
                java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(p.getProperty(key)))));
        return keys;
    }
    public JSONObject handle(ProfileRegistry.Identity identity, HarnessAction action, JSONObject data, byte[] raw) throws Exception {
        String tenant = identity.tenant, id = data.optString("id"), version = data.optString("approvalId");
        String hash = JdbcRunStore.hash(new JSONArray().put(action.name).put(id).put(version).put(RuntimeSupport.sha(raw)).put(data.optString("signer")).put(data.optString("signature")));
        boolean write = action.method.equals("POST") || action.method.equals("PUT");
        if (write && action != HarnessAction.ValidatePackage) {
            JSONObject replay = metadata.replay(tenant, identity.subject, data.getString("key"), hash, null);
            if (replay != null) return replay;
        }
        JSONObject result;
        switch (action) {
            case ValidatePackage:
            case PublishRelease:
                if (!data.optString("contentType").equals("application/zip")) throw new StoreException("UNSUPPORTED_MEDIA_TYPE");
                PackageRepository.Validated checked = repository.validate(new ByteArrayInputStream(raw));
                if (broker != null) broker.validatePackage(checked);
                if (action == HarnessAction.ValidatePackage) return new JSONObject().put("digest", checked.digest).put("manifest", checked.manifest);
                // Remote industry authors publish resource packages. Executable tools remain deployment assets.
                JSONArray tools = checked.manifest.getJSONArray("tools");
                for (int i = 0; i < tools.length(); i++) if (tools.getJSONObject(i).getString("kind").equals("executable")) throw new StoreException("ADMIN_EXECUTABLE_FORBIDDEN");
                String signer = data.optString("signer");
                if (!Arrays.asList(config.getProperty("admin.tenant." + tenant + ".signers", "").split(",")).contains(signer) || signer.isEmpty()) throw new StoreException("ADMIN_SIGNER_FORBIDDEN");
                result = repository.upload(tenant, identity.subject, "remote-author", new ByteArrayInputStream(raw), signer, data.optString("signature"));
                break;
            case ReadRelease: return catalog.release(tenant, id);
            case PublishPolicy:
                JSONObject policy = StrictJson.object(raw);
                if (!RuntimeSupport.strings(policy.getJSONArray("permissions")).stream().allMatch(permissions::contains)) throw new StoreException("ADMIN_PERMISSION_FORBIDDEN");
                result = profiles.publish(tenant, policy); break;
            case ReadPolicyBinding:
                result = catalog.binding(tenant, "policy", "default"); break;
            case BindPolicy:
                JSONObject binding = StrictJson.object(raw); keys(binding, "target", "expectedVersion");
                result = catalog.bindRemote(tenant, identity.subject, data.getString("key"), hash, binding.getString("target"), binding.getLong("expectedVersion")); break;
            case PutSource:
                JSONObject source = StrictJson.object(raw);
                validateSource(source);
                result = metadata.putSource(tenant, id, version, source); break;
            case ReadSource:
                JSONObject value = metadata.source(tenant, id + "@" + version);
                return new JSONObject().put("sourceRef", id + "@" + version).put("digest", JdbcRunStore.hash(value)).put("config", value);
            case SourceGrant:
                JSONObject grant = StrictJson.object(raw); keys(grant, "subject", "sourceRef", "allowed");
                result = metadata.grantRemote(tenant, identity.subject, data.getString("key"), hash, grant.getString("subject"), grant.getString("sourceRef"), grant.getBoolean("allowed")); break;
            default: throw new StoreException("UNKNOWN_ACTION");
        }
        return write ? metadata.replay(tenant, identity.subject, data.getString("key"), hash, result) : result;
    }
    public void validateSource(JSONObject source) throws Exception {
        keys(source, "baseUrl", "secretRef", "identityMapping", "allowedPaths");
        URI uri = new URI(source.getString("baseUrl"));
        String base = uri.toASCIIString();
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                || !Arrays.asList(config.getProperty("admin.sources.allowedOrigins", "").split(",")).contains(base)
                || !Arrays.asList(config.getProperty("admin.sources.secretRefs", "").split(",")).contains(source.getString("secretRef"))
                || !source.getString("identityMapping").equals("cube-subject-hmac-v1")) throw new StoreException("DATA_SOURCE_FORBIDDEN");
        JSONArray paths = source.getJSONArray("allowedPaths");
        if (paths.length() < 1 || paths.length() > 16) throw new StoreException("DATA_SOURCE_FORBIDDEN");
        Set<String> allowed = new HashSet<>(Arrays.asList(config.getProperty("admin.sources.allowedPaths", "").split(",")));
        for (int i = 0; i < paths.length(); i++) if (!paths.getString(i).matches("/[A-Za-z0-9_/-]+") || !allowed.contains(paths.getString(i))) throw new StoreException("DATA_SOURCE_FORBIDDEN");
    }
    private static void keys(JSONObject value, String... keys) {
        if (!value.keySet().equals(new HashSet<>(Arrays.asList(keys)))) throw new StoreException("SCHEMA_INVALID");
    }
}
