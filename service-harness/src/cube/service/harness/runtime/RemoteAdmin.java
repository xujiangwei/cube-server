/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.common.action.HarnessAction;
import cube.service.harness.execution.ToolBroker;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.ProviderStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 对 RuntimeAdmin 所用的同一套包/策略/CAS 服务的远程门面。
 */
public final class RemoteAdmin {

    /**
     * 目录存储。
     */
    private final CatalogStore catalog;

    /**
     * 包仓库。
     */
    private final PackageRepository repository;

    /**
     * 提供方元数据存储。
     */
    private final ProviderStore metadata;

    /**
     * profile 注册表。
     */
    private final ProfileRegistry profiles;

    /**
     * 工具 Broker，可为 null。
     */
    private final ToolBroker broker;

    /**
     * 部署私有配置，读取管理员签名者白名单与数据源约束。
     */
    private final Properties config;

    /**
     * 调用方已授予的权限集合。
     */
    private final Set<String> permissions;

    /**
     * 构造远程管理门面。
     *
     * @param catalog     目录存储。
     * @param repository  包仓库。
     * @param metadata    提供方元数据存储。
     * @param profiles    profile 注册表。
     * @param broker      工具 Broker，可为 null。
     * @param config      部署私有配置。
     * @param permissions 调用方权限集合。
     */
    public RemoteAdmin(CatalogStore catalog, PackageRepository repository, ProviderStore metadata,
                       ProfileRegistry profiles, ToolBroker broker, Properties config, Set<String> permissions) {
        this.catalog = catalog;
        this.repository = repository;
        this.metadata = metadata;
        this.profiles = profiles;
        this.broker = broker;
        this.config = config;
        this.permissions = permissions;
    }

    /**
     * 从部署参数装载管理员签名者公钥。
     *
     * @param p 部署参数，键为 runtime.signer.&lt;name&gt;，值为 Base64 编码的 RSA 公钥。
     * @return 签名者名称到公钥的映射。
     * @throws Exception 密钥格式非法时抛出。
     */
    public static Map<String, PublicKey> signers(Properties p) throws Exception {
        Map<String, PublicKey> keys = new HashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("runtime.signer.")) {
                keys.put(key.substring(15), KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p.getProperty(key)))));
            }
        }
        return keys;
    }

    /**
     * 处理一次远程管理动作，含幂等重放与授权校验。
     *
     * @param identity 调用方身份。
     * @param action   管理动作。
     * @param data     动作参数。
     * @param raw      请求体字节。
     * @return 动作结果。
     * @throws Exception 授权、schema 或存储失败时抛出。
     */
    public JSONObject handle(ProfileRegistry.Identity identity, HarnessAction action, JSONObject data, byte[] raw) throws Exception {
        String tenant = identity.tenant;
        String id = data.optString("id");
        String version = data.optString("approvalId");
        String hash = JdbcRunStore.hash(new JSONArray()
                .put(action.name)
                .put(id)
                .put(version)
                .put(RuntimeSupport.sha(raw))
                .put(data.optString("signer"))
                .put(data.optString("signature")));
        boolean write = action.method.equals("POST") || action.method.equals("PUT");
        if (write && action != HarnessAction.ValidatePackage) {
            JSONObject replay = metadata.replay(tenant, identity.subject, data.getString("key"), hash, null);
            if (replay != null) {
                return replay;
            }
        }
        JSONObject result;
        switch (action) {
            case ValidatePackage:
            case PublishRelease:
                if (!data.optString("contentType").equals("application/zip")) {
                    throw new StoreException("UNSUPPORTED_MEDIA_TYPE");
                }
                PackageRepository.Validated checked = repository.validate(new ByteArrayInputStream(raw));
                if (broker == null && ToolBroker.hasCompletionRequirements(checked.manifest)) {
                    throw new StoreException("WORKFLOW_GUARD_UNAVAILABLE");
                }
                if (broker != null) {
                    broker.validatePackage(checked);
                }
                if (action == HarnessAction.ValidatePackage) {
                    return new JSONObject()
                            .put("digest", checked.digest)
                            .put("manifest", checked.manifest);
                }
                // 远程行业作者发布资源包；可执行工具仍是部署资产。
                JSONArray tools = checked.manifest.getJSONArray("tools");
                for (int i = 0; i < tools.length(); i++) {
                    if (tools.getJSONObject(i).getString("kind").equals("executable")) {
                        throw new StoreException("ADMIN_EXECUTABLE_FORBIDDEN");
                    }
                }
                String signer = data.optString("signer");
                if (!Arrays.asList(config.getProperty("admin.tenant." + tenant + ".signers", "").split(",")).contains(signer) || signer.isEmpty()) {
                    throw new StoreException("ADMIN_SIGNER_FORBIDDEN");
                }
                result = repository.upload(tenant, identity.subject, "remote-author", new ByteArrayInputStream(raw), signer, data.optString("signature"));
                break;
            case ReadRelease:
                return catalog.release(tenant, id);
            case PublishPolicy:
                JSONObject policy = StrictJson.object(raw);
                if (!RuntimeSupport.strings(policy.getJSONArray("permissions")).stream().allMatch(permissions::contains)) {
                    throw new StoreException("ADMIN_PERMISSION_FORBIDDEN");
                }
                result = profiles.publish(tenant, policy);
                break;
            case ReadPolicyBinding:
                result = catalog.binding(tenant, "policy", "default");
                break;
            case BindPolicy:
                JSONObject binding = StrictJson.object(raw);
                keys(binding, "target", "expectedVersion");
                result = catalog.bindRemote(tenant, identity.subject, data.getString("key"), hash, binding.getString("target"), binding.getLong("expectedVersion"));
                break;
            case PutSource:
                JSONObject source = StrictJson.object(raw);
                validateSource(source);
                result = metadata.putSource(tenant, id, version, source);
                break;
            case ReadSource:
                JSONObject value = metadata.source(tenant, id + "@" + version);
                return new JSONObject()
                        .put("sourceRef", id + "@" + version)
                        .put("digest", JdbcRunStore.hash(value))
                        .put("config", value);
            case SourceGrant:
                JSONObject grant = StrictJson.object(raw);
                keys(grant, "subject", "sourceRef", "allowed");
                result = metadata.grantRemote(tenant, identity.subject, data.getString("key"), hash, grant.getString("subject"), grant.getString("sourceRef"), grant.getBoolean("allowed"));
                break;
            default:
                throw new StoreException("UNKNOWN_ACTION");
        }
        return write ? metadata.replay(tenant, identity.subject, data.getString("key"), hash, result) : result;
    }

    /**
     * 校验数据源配置的字段、来源与路径白名单。
     *
     * @param source 数据源配置。
     * @throws Exception 配置非法时抛出。
     */
    public void validateSource(JSONObject source) throws Exception {
        keys(source, "baseUrl", "secretRef", "identityMapping", "allowedPaths");
        URI uri = new URI(source.getString("baseUrl"));
        String base = uri.toASCIIString();
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                || !Arrays.asList(config.getProperty("admin.sources.allowedOrigins", "").split(",")).contains(base)
                || !Arrays.asList(config.getProperty("admin.sources.secretRefs", "").split(",")).contains(source.getString("secretRef"))
                || !source.getString("identityMapping").equals("cube-subject-hmac-v1")) {
            throw new StoreException("DATA_SOURCE_FORBIDDEN");
        }
        JSONArray paths = source.getJSONArray("allowedPaths");
        if (paths.length() < 1 || paths.length() > 16) {
            throw new StoreException("DATA_SOURCE_FORBIDDEN");
        }
        Set<String> allowed = new HashSet<>(Arrays.asList(config.getProperty("admin.sources.allowedPaths", "").split(",")));
        for (int i = 0; i < paths.length(); i++) {
            if (!paths.getString(i).matches("/[A-Za-z0-9_/-]+") || !allowed.contains(paths.getString(i))) {
                throw new StoreException("DATA_SOURCE_FORBIDDEN");
            }
        }
    }

    /**
     * 校验对象的字段集合恰好等于给定键集合。
     *
     * @param value 待校验对象。
     * @param keys  允许的字段名。
     */
    private static void keys(JSONObject value, String... keys) {
        if (!value.keySet().equals(new HashSet<>(Arrays.asList(keys)))) {
            throw new StoreException("SCHEMA_INVALID");
        }
    }
}
