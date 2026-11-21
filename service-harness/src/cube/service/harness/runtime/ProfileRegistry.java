/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.protocol.Contract;
import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.FileStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 将租户业务策略与服务端执行配置解析为不可变的 run。
 */
public final class ProfileRegistry {

    /**
     * 平台级权限集合，始终由服务端授予，不由租户策略下发。
     */
    public static final Set<String> PLATFORM_PERMISSIONS = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
            "skill.list", "skill.activate", "resource.list", "resource.read", "resource.search",
            "input.read_text", "artifact.write_text")));

    /**
     * 目录存储。
     */
    private final CatalogStore catalog;

    /**
     * 文件服务，未启用时为 null。
     */
    private final FileStore files;

    /**
     * 服务端执行配置，未配置时拒绝创建 run。
     */
    private final ExecutionProfiles deployment;

    /**
     * 仅持有目录存储的构造器。
     *
     * @param catalog 目录存储。
     */
    public ProfileRegistry(CatalogStore catalog) {
        this(catalog, null, null);
    }

    /**
     * 持有目录与文件服务的构造器。
     *
     * @param catalog 目录存储。
     * @param files   文件服务。
     */
    public ProfileRegistry(CatalogStore catalog, FileStore files) {
        this(catalog, files, null);
    }

    /**
     * 完整构造器。
     *
     * @param catalog    目录存储。
     * @param files      文件服务，可为 null。
     * @param deployment 服务端执行配置，可为 null。
     */
    public ProfileRegistry(CatalogStore catalog, FileStore files, ExecutionProfiles deployment) {
        this.catalog = catalog;
        this.files = files;
        this.deployment = deployment;
    }

    /**
     * 受信身份；绝不从客户端请求体构造。
     */
    public static final class Identity {
        /**
         * 租户标识。
         */
        public final String tenant;

        /**
         * 联系人标识。
         */
        public final String subject;

        /**
         * 已授予权限集合，不可修改。
         */
        public final Set<String> permissions;

        /**
         * 从受信来源构造身份。
         *
         * @param tenant      租户标识。
         * @param subject     联系人标识。
         * @param permissions 权限集合。
         */
        public Identity(String tenant, String subject, Set<String> permissions) {
            this.tenant = RuntimeSupport.id(tenant);
            this.subject = RuntimeSupport.id(subject);
            this.permissions = Collections.unmodifiableSet(new TreeSet<>(permissions));
        }
    }

    /**
     * 发布租户 profile 定义，仅允许 policy 类型。
     *
     * @param tenant     租户标识。
     * @param definition profile 定义。
     * @return 已发布的 profile。
     */
    public JSONObject publish(String tenant, JSONObject definition) {
        String kind = definition.getString("kind");
        if (!kind.equals("policy")) {
            throw RuntimeSupport.error("EXECUTION_CONFIGURATION_IS_SERVER_MANAGED");
        }
        validateDefinition(definition);
        if (definition.has("skillReleases")) {
            for (String digest : RuntimeSupport.strings(definition.getJSONArray("skillReleases"))) {
                catalog.release(tenant, digest);
            }
        }
        return catalog.putProfile(tenant, RuntimeSupport.copy(definition));
    }

    /**
     * 校验 profile 定义的类型、schema 与引用字段格式。
     *
     * @param definition profile 定义。
     */
    public static void validateDefinition(JSONObject definition) {
        String kind = definition.getString("kind");
        if (!Arrays.asList("model", "runtime", "policy").contains(kind)) {
            throw RuntimeSupport.error("UNKNOWN_PROFILE_KIND");
        }
        RuntimeSupport.validate(kind.equals("policy") ? "tenant-policy" : kind + "-profile", definition);
        for (String key : Arrays.asList("capabilities", "skillReleases", "permissions", "requiredCapabilities")) {
            if (definition.has(key)) {
                RuntimeSupport.strings(definition.getJSONArray(key));
            }
        }
    }

    /**
     * 在创建事务内、幂等重放之后解析 run，无额外准入钩子。
     *
     * @param identity 调用方身份。
     * @param key      幂等键。
     * @param request  创建请求。
     * @return 已固定的 run 配置。
     */
    public JSONObject createRun(Identity identity, String key, JSONObject request) {
        return createRun(identity, key, request, pin -> {
        });
    }

    /**
     * 在创建事务内解析 run，并调用准入钩子。
     *
     * @param identity  调用方身份。
     * @param key       幂等键。
     * @param request   创建请求。
     * @param admission 准入钩子，在固定配置上追加校验。
     * @return 已固定的 run 配置。
     */
    public JSONObject createRun(Identity identity, String key, JSONObject request, Consumer<JSONObject> admission) {
        if (deployment == null) {
            throw RuntimeSupport.error("EXECUTION_CONFIGURATION_REQUIRED");
        }
        Contract.parse("create-run", RuntimeSupport.bytes(request));
        // 幂等命名空间在服务端配置变更之间保持稳定。
        return catalog.createRun(identity.tenant, identity.subject, key, "server-default", request, c -> {
            validateAttachments(request);
            bindAttachments(c, identity, request);
            JSONObject policy = catalog.tenantPolicy(c, identity.tenant);
            RuntimeSupport.validate("tenant-policy", policy);
            JSONObject business = policy.getJSONObject("businessLimits");
            catalog.checkAdmission(c, identity.tenant, business.getInt("maxActiveRuns"));
            JSONObject pin = deployment.select(request);
            JSONObject execution = pin.getJSONObject("execution");
            if (request.has("parentRunId")) {
                pin.put("conversationHistory", catalog.conversation(c,
                        identity.tenant, identity.subject, request.getString("parentRunId"), files));
            }
            JSONObject limits = execution.getJSONObject("executionLimits");
            limits.put("maxLifetimeMillis", Math.min(limits.getLong("maxLifetimeMillis"), business.getLong("maxLifetimeMillis")));
            RunSelection resolver = new RunSelection(catalog, files != null);
            resolver.resolve(identity, policy, resolver.releases(c, identity, policy), request, pin);
            admission.accept(pin);
            return pin;
        });
    }

    /**
     * 经认证的能力目录；绝不导出私有后端配置。
     *
     * @param identity 调用方身份。
     * @return 能力目录。
     */
    public JSONObject describe(Identity identity) {
        try {
            JSONObject policy = catalog.tenantPolicy(identity.tenant);
            RunSelection resolver = new RunSelection(catalog, files != null);
            JSONArray releases = resolver.releases(null, identity, policy);
            Set<String> granted = RunSelection.permissions(identity, policy, releases);
            JSONArray models = new JSONArray();
            JSONArray runtimes = new JSONArray();
            JSONArray skills = new JSONArray();
            for (JSONObject model : deployment.list("model")) {
                models.put(ExecutionProfiles.ref(model)
                        .put("capabilities", model.getJSONArray("capabilities"))
                        .put("nativeTools", model.optBoolean("nativeTools", false))
                        .put("current", deployment.current(model)));
            }
            for (JSONObject runtime : deployment.list("runtime")) {
                runtimes.put(ExecutionProfiles.ref(runtime)
                        .put("mode", runtime.getString("mode"))
                        .put("capabilities", runtime.getJSONArray("capabilities"))
                        .put("current", deployment.current(runtime)));
            }
            if (granted.contains("skill.list")) {
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.getJSONObject(i);
                    JSONObject manifest = release.getJSONObject("manifest");
                    skills.put(new JSONObject()
                            .put("name", manifest.getString("name"))
                            .put("version", manifest.getString("version"))
                            .put("digest", release.getString("digest"))
                            .put("dependencies", release.getJSONArray("dependencies"))
                            .put("compatibleRuntimes", compatibleRuntimes(release, releases)));
                }
            }
            JSONArray tools = resolver.tools(granted, granted.contains("skill.list") ? releases : new JSONArray());
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                JSONObject release = tool.has("release") ? CatalogStore.requireRelease(new JSONObject().put("releases", releases), tool.getString("release")) : null;
                tool.put("compatibleRuntimes", compatibleRuntimes(release, releases));
            }
            return new JSONObject()
                    .put("defaults", deployment.defaults())
                    .put("models", models)
                    .put("runtimes", runtimes)
                    .put("skills", skills)
                    .put("tools", tools);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Capability catalog could not be read", e);
        }
    }

    /**
     * 计算与依赖约束兼容的 runtime 引用列表。
     *
     * @param release  发布项，可为 null。
     * @param releases 全部发布项。
     * @return 兼容的 runtime 引用数组。
     */
    private JSONArray compatibleRuntimes(JSONObject release, JSONArray releases) {
        JSONArray out = new JSONArray();
        for (JSONObject runtime : deployment.list("runtime")) {
            try {
                if (release == null || RunSelection.compatibleWithDependencies(release, releases, runtime)) {
                    out.put(ExecutionProfiles.ref(runtime));
                }
            } catch (StoreException e) {
                if (!e.code.equals("DEPENDENCY_NOT_PINNED")) {
                    throw e;
                }
            }
        }
        return out;
    }

    /**
     * 校验附件请求与文件服务能力一致。
     *
     * @param request 创建请求。
     */
    private void validateAttachments(JSONObject request) {
        if (files == null && request.getJSONObject("input").getJSONArray("attachmentIds").length() != 0) {
            throw RuntimeSupport.error("ATTACHMENTS_NOT_IMPLEMENTED");
        }
    }

    /**
     * 校验并绑定请求中的附件引用。
     *
     * @param c        数据库连接。
     * @param identity 调用方身份。
     * @param request  创建请求。
     * @throws Exception 绑定校验失败时抛出。
     */
    private void bindAttachments(Connection c, Identity identity, JSONObject request) throws Exception {
        if (files != null) {
            files.validateBindings(c, identity.tenant, identity.subject,
                    request.getJSONObject("input").getJSONArray("attachmentIds"));
        }
    }

    /**
     * 读取 run 的已固定配置。
     *
     * @param identity 调用方身份。
     * @param runId    run 标识。
     * @return 已固定的配置。
     */
    public JSONObject pinned(Identity identity, String runId) {
        return catalog.pinned(identity.tenant, identity.subject, runId);
    }
}
