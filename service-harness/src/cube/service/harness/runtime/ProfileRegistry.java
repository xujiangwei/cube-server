/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * 画像注册表：可信身份 → 不可变 Task/Model/Runtime 定义 → 每 run 原子固定。
 *
 * <p>{@link #publish} 只接受封闭 schema 校验过的 Model/Runtime/TaskProfile；
 * task 引用的 model/runtime/skillRelease 必须已存在，同 kind/id/version 异内容
 * 由存储层拒绝。{@link #createRun} 在创建事务内解析当前 active task 绑定，
 * 固定 model/runtime/release/权限及引用后提交；幂等重试即使 active 已切换也返回原 run。
 *
 * <p>权限模型：run 的有效权限是 TaskProfile 声明权限、可信身份权限与
 * 已固定 release 声明权限及平台权限的交集；Identity 只能由可信身份源构造，
 * 禁止把客户端请求字段映射成身份。
 */
public final class ProfileRegistry {

    /**
     * 平台核心权限全集：资源能力（skill.list/activate、resource.list/read/search）
     * 与文件能力（input.read_text、artifact.write_text）；业务权限不在此列举。
     */
    public static final Set<String> PLATFORM_PERMISSIONS = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
            "skill.list",
            "skill.activate",
            "resource.list",
            "resource.read",
            "resource.search",
            "input.read_text", "artifact.write_text")));

    /**
     * 目录存储：profile 发布/解析、active 绑定、release 查询与 run 创建均经此访问。
     */
    private final CatalogStore catalog;

    /**
     * 文件存储；为 null 表示文件服务未启用，此时拒绝任何非空附件。
     */
    private final cube.service.harness.store.FileStore files;

    /**
     * 构造注册表（文件服务未启用）。
     *
     * @param catalog 目录存储
     */
    public ProfileRegistry(CatalogStore catalog) {
        this(catalog, null);
    }

    /**
     * 构造注册表。
     *
     * @param catalog 目录存储
     * @param files   文件存储；传 {@code null} 表示不启用附件绑定
     */
    public ProfileRegistry(CatalogStore catalog, cube.service.harness.store.FileStore files) {
        this.catalog = catalog;
        this.files = files;
    }

    /**
     * 可信身份三元组：租户、主体与权限集合。
     *
     * <p>权限在构造时排序去重并冻结；身份一旦创建不可变，
     * 保证后续每次授权判定都基于同一份快照。
     */
    public static final class Identity {
        /**
         * 租户标识与主体标识；均须符合运行时 id 规则。
         */
        public final String tenant, subject;

        /**
         * 身份持有的权限集合；排序去重后的不可变视图。
         */
        public final Set<String> permissions;

        /**
         * 仅可信身份提供方/服务可构造；严禁把客户端请求字段映射成身份。
         *
         * @param tenant      租户标识
         * @param subject     主体标识
         * @param permissions 身份权限集合；内部复制为排序不可变集合
         */
        public Identity(String tenant, String subject, Set<String> permissions) {
            this.tenant = RuntimeSupport.id(tenant);
            this.subject = RuntimeSupport.id(subject);
            this.permissions = Collections.unmodifiableSet(new TreeSet<>(permissions));
        }
    }

    /**
     * 发布 Model/Runtime/TaskProfile：封闭 schema 校验 + 引用存在性检查。
     *
     * <p>task 还要求 model/runtime 引用已发布、skillReleases 全部已存在，
     * 且各声明数组无重复项。同 kind/id/version 的异内容由存储层拒绝。
     *
     * @param tenant     租户标识
     * @param definition profile 定义；必须含 kind 字段且为 model/runtime/task 之一
     * @return 存储层写入后的 profile 行
     * @throws cube.service.harness.store.StoreException kind 未知（UNKNOWN_PROFILE_KIND）、
     *                                                   schema 校验失败、引用不存在或声明重复（DUPLICATE_DECLARATION）
     */
    public JSONObject publish(String tenant, JSONObject definition) {
        String kind = definition.getString("kind");
        if (!Arrays.asList("model", "runtime", "task").contains(kind)) {
            throw RuntimeSupport.error("UNKNOWN_PROFILE_KIND");
        }
        RuntimeSupport.validate(kind + "-profile", definition);
        if (definition.has("capabilities")) {
            RuntimeSupport.strings(definition.getJSONArray("capabilities"));
        }
        if (kind.equals("task")) {
            RuntimeSupport.strings(definition.getJSONArray("skillReleases"));
            RuntimeSupport.strings(definition.getJSONArray("permissions"));
            RuntimeSupport.strings(definition.getJSONArray("requiredCapabilities"));
            for (String refKind : Arrays.asList("model", "runtime")) {
                JSONObject ref = definition.getJSONObject(refKind);
                catalog.profile(tenant, refKind, ref.getString("id"), ref.getString("version"));
            }
            for (String digest : RuntimeSupport.strings(definition.getJSONArray("skillReleases"))) {
                catalog.release(tenant, digest);
            }
        }
        return catalog.putProfile(tenant, RuntimeSupport.copy(definition));
    }

    /**
     * 创建 run：在创建事务内固定 task/model/runtime/release/权限后提交。
     *
     * <p>请求先过 create-run 契约校验，taskProfileId 必须与路径参数一致；
     * 文件服务未启用时拒绝非空 attachmentIds。事务内依次：校验附件绑定、
     * 解析 active task 绑定（{@code id@version}）、固定三代 profile 并复验 schema、
     * 核对 model+runtime 能力覆盖 task 必需能力、固定全部 ready release
     * （技能名不可重复、manifest 的 runtime 必须与 task 引用一致、依赖必须全部被固定），
     * 最后按 task 声明 ∩ 身份 ∩（平台 + release 声明）收敛权限。
     *
     * @param identity 可信身份；tenant/subject 即 run 归属
     * @param key      幂等键；同 key 重试返回原 run
     * @param taskId   task profile id；必须与请求体内的 taskProfileId 一致
     * @param request  create-run 请求体
     * @return 固定结果：task/model/runtime/releases/permissions 的不可变快照
     * @throws cube.service.harness.store.StoreException 契约不符（PROFILE_REQUEST_MISMATCH）、
     *                                                   附件未实现（ATTACHMENTS_NOT_IMPLEMENTED）、能力不匹配
     *                                                   （PROFILE_CAPABILITY_MISMATCH）、release 未就绪（RELEASE_NOT_READY）、
     *                                                   技能重复（PROFILE_DUPLICATE_SKILL）、runtime 不匹配（PACKAGE_RUNTIME_MISMATCH）、
     *                                                   依赖未固定（DEPENDENCY_NOT_PINNED）等
     */
    public JSONObject createRun(Identity identity, String key, String taskId, JSONObject request) {
        cube.service.harness.protocol.Contract.parse("create-run", RuntimeSupport.bytes(request));
        if (!request.getString("taskProfileId").equals(taskId)) {
            throw RuntimeSupport.error("PROFILE_REQUEST_MISMATCH");
        }
        if (files == null && request.getJSONObject("input").getJSONArray("attachmentIds").length() != 0) {
            throw RuntimeSupport.error("ATTACHMENTS_NOT_IMPLEMENTED");
        }
        return catalog.createRun(identity.tenant, identity.subject, key, RuntimeSupport.id(taskId), request, c -> {
            // 附件绑定：仅验证并绑定本主体 ready、未到期的 Input。
            if (files != null) {
                files.validateBindings(c, identity.tenant, identity.subject, request.getJSONObject("input").getJSONArray("attachmentIds"));
            }
            // active 绑定形如 id@version，据此解析当前生效的 task 及三代 profile。
            String binding = catalog.active(c, identity.tenant, "task", taskId).getString("target_ref");
            String[] ref = binding.split("@", -1);
            JSONObject task = catalog.profile(c, identity.tenant, "task", ref[0], ref[1]);
            JSONObject mr = task.getJSONObject("model"), rr = task.getJSONObject("runtime");
            JSONObject model = catalog.profile(c, identity.tenant, "model", mr.getString("id"), mr.getString("version"));
            RuntimeSupport.validate("task-profile", task);
            RuntimeSupport.validate("model-profile", model);
            JSONObject runtime = catalog.profile(c, identity.tenant, "runtime", rr.getString("id"), rr.getString("version"));
            RuntimeSupport.validate("runtime-profile", runtime);
            // 能力核算：model 与 runtime 的能力并集必须覆盖 task 的必需能力。
            Set<String> available = RuntimeSupport.strings(model.getJSONArray("capabilities"));
            available.addAll(RuntimeSupport.strings(runtime.getJSONArray("capabilities")));
            if (!available.containsAll(RuntimeSupport.strings(task.getJSONArray("requiredCapabilities")))) {
                throw RuntimeSupport.error("PROFILE_CAPABILITY_MISMATCH");
            }
            // 权限初值：task 声明权限与身份权限取交集。
            Set<String> permissions = RuntimeSupport.strings(task.getJSONArray("permissions"));
            permissions.retainAll(identity.permissions);
            // 声明侧权限：平台权限 + 各固定 release manifest 声明权限。
            Set<String> declaredPermissions = new HashSet<>(PLATFORM_PERMISSIONS);
            JSONArray releases = new JSONArray();
            Set<String> skillNames = new HashSet<>();
            for (String digest : RuntimeSupport.strings(task.getJSONArray("skillReleases"))) {
                JSONObject row = catalog.release(c, identity.tenant, digest, true);
                if (!row.getString("state").equals("ready")) {
                    throw RuntimeSupport.error("RELEASE_NOT_READY");
                }
                JSONObject manifest = new JSONObject(row.getString("manifest_json"));
                RuntimeSupport.validate("package-manifest", manifest);
                declaredPermissions.addAll(RuntimeSupport.strings(manifest.getJSONArray("permissions")));
                // 同一技能名的多个 release 不能同时固定。
                if (!skillNames.add(manifest.getString("name"))) {
                    throw RuntimeSupport.error("PROFILE_DUPLICATE_SKILL");
                }
                // 包的 runtime 声明必须与 task 引用的 runtime 规范化一致。
                if (!JdbcRunStore.canonical(manifest.getJSONObject("runtime")).equals(JdbcRunStore.canonical(rr))) {
                    throw RuntimeSupport.error("PACKAGE_RUNTIME_MISMATCH");
                }
                releases.put(new JSONObject().put("digest", digest).put("manifest", manifest));
                // 传递依赖也必须被本 task 固定，避免固定面外内容。
                JSONObject provenance = new JSONObject(row.getString("provenance_json"));
                for (String dep : RuntimeSupport.strings(provenance.optJSONArray("dependencies") == null
                        ? new JSONArray() : provenance.getJSONArray("dependencies"))) {
                    if (!RuntimeSupport.strings(task.getJSONArray("skillReleases")).contains(dep)) {
                        throw RuntimeSupport.error("DEPENDENCY_NOT_PINNED");
                    }
                }
            }
            // 权限终值：再与声明侧取交集，得到本 run 的有效权限。
            permissions.retainAll(declaredPermissions);
            return new JSONObject().put("schemaVersion", 1).put("task", task).put("model", model).put("runtime", runtime)
                    .put("releases", releases).put("permissions", new JSONArray(permissions));
        });
    }

    /**
     * 读取 run 的固定快照（tenant/subject 强制匹配当前身份）。
     *
     * @param identity 可信身份
     * @param runId    run 标识
     * @return 固定快照 JSON
     */
    public JSONObject pinned(Identity identity, String runId) {
        return catalog.pinned(identity.tenant, identity.subject, runId);
    }
}
