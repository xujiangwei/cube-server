/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 元数据存取：release、binding、profile、run 配置固定、workspace 与 context。
 *
 * <p>全部操作与 RunStore 共享同一事务与租户/属主（tenant/subject）边界，
 * 复用 {@code store.tx} 的隐式事务；MySQL 下读关键行加 {@code FOR UPDATE}
 * 行锁，SQLite 依赖 BEGIN IMMEDIATE 写锁。写入采用乐观版本号
 * （version/CAS）与内容摘要幂等，错误以稳定 StoreException code 上抛。
 */
public final class CatalogStore {
    /**
     * 共享的 RunStore，提供事务边界、run 行访问、身份/版本/事件等基础操作。
     */
    private final JdbcRunStore store;

    /**
     * 是否 MySQL 方言；决定读关键行是否追加 " FOR UPDATE" 行锁后缀。
     */
    private final boolean mysql;

    /**
     * 绑定共享 RunStore 与方言标志。
     *
     * @param store 共享的 RunStore 实例
     * @param mysql true 为 MySQL（行锁可用），false 为 SQLite
     */
    public CatalogStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        this.mysql = mysql;
    }

    /**
     * 行锁后缀：MySQL 返回 " FOR UPDATE"，SQLite 返回空串。
     *
     * @return 拼接到 SELECT 末尾的行锁后缀
     */
    private String lock() {
        return mysql ? " FOR UPDATE" : "";
    }

    /** Atomically initialize tenant business policy from a validated startup snapshot. */
    public void ensureTenant(String tenant, cube.service.harness.runtime.TenantTemplates templates) {
        id(tenant);
        store.tx(c -> {
            JSONObject existing = first(rows(c, "SELECT initialized_at FROM harness_tenant WHERE tenant=?" + lock(), tenant));
            if (existing != null && existing.getLong("initialized_at") > 0) return null;
            update(c, mysql
                    ? "INSERT INTO harness_tenant (tenant,initialized_at,template_digest) VALUES (?,0,NULL) ON DUPLICATE KEY UPDATE tenant=VALUES(tenant)"
                    : "INSERT OR IGNORE INTO harness_tenant (tenant,initialized_at,template_digest) VALUES (?,0,NULL)", tenant);
            // The unique tenant row serializes first access across requests and JVMs.
            existing = first(rows(c, "SELECT initialized_at FROM harness_tenant WHERE tenant=?" + lock(), tenant));
            if (existing.getLong("initialized_at") > 0) return null;
            boolean configured = first(rows(c, "SELECT tenant FROM harness_binding WHERE tenant=? AND binding_kind='policy' AND binding_name='default'", tenant)) != null;
            if (!configured) {
                for (JSONObject definition : templates.profiles()) {
                    String kind = definition.getString("kind"), name = definition.getString("id"), version = definition.getString("version");
                    update(c, "INSERT INTO harness_profile (tenant,profile_kind,profile_id,profile_version,digest,definition_json) VALUES (?,?,?,?,?,?)",
                            tenant, kind, name, version, hash(definition), canonical(definition));
                    if (kind.equals("policy")) {
                        update(c, "INSERT INTO harness_binding (tenant,binding_kind,binding_name,target_ref,version) VALUES (?,'policy',?,?,1)",
                                tenant, name, name + "@" + version);
                    }
                }
            }
            update(c, "UPDATE harness_tenant SET initialized_at=?,template_digest=? WHERE tenant=?",
                    store.now(c), templates.digest, tenant);
            return null;
        });
    }

    /**
     * run 创建时的权威输入解析回调。
     */
    public interface Resolver {
        /**
         * 在事务连接上解析权威配置。
         *
         * @param connection 事务内的数据库连接
         * @return 解析出的权威配置 JSON（服务器 execution/model/runtime 与租户 policy）
         * @throws Exception 解析失败即导致整个创建事务回滚
         */
        JSONObject resolve(Connection connection) throws Exception;
    }

    /**
     * workspace 行校验通过后执行的动作回调。
     *
     * @param <T> 动作返回值类型
     */
    public interface WorkspaceAction<T> {
        /**
         * 对已通过租约校验的 workspace 行执行动作。
         *
         * @param workspace 当前 workspace 行（含 owner_id、fence、expires_at 等字段）
         * @return 动作结果，随事务返回调用方
         * @throws Exception 动作失败即导致整个事务回滚
         */
        T apply(JSONObject workspace) throws Exception;
    }

    /**
     * 登记一个 release；同 (tenant, digest) 重复登记返回既有行（幂等）。
     *
     * <p>依赖必须全部为 ready，否则拒绝；新行以 state='ready'、version=1 落库。
     *
     * @param tenant     租户标识
     * @param digest     release 内容摘要
     * @param manifest   release 清单 JSON，至少包含 name 字段
     * @param provenance 出处 JSON，必须包含 dependencies 数组
     * @return 入库后的 release 行
     * @throws StoreException DEPENDENCY_NOT_READY（依赖未就绪）
     */
    public JSONObject putRelease(String tenant, String digest, JSONObject manifest, JSONObject provenance) {
        return store.tx(c -> {
            JSONObject old = release(c, tenant, digest, false);
            if (old != null) {
                return old;
            }
            JSONArray dependencies = provenance.getJSONArray("dependencies");
            for (int i = 0; i < dependencies.length(); i++) {
                if (!release(c, tenant, dependencies.getString(i), true).getString("state").equals("ready")) {
                    throw new StoreException("DEPENDENCY_NOT_READY");
                }
            }
            update(c, "INSERT INTO harness_release VALUES (?,?,?,?,?,'ready',1)", id(tenant), digest, manifest.getString("name"), canonical(manifest), canonical(provenance));
            return release(c, tenant, digest, true);
        });
    }

    /**
     * 查询单个 release 行（可指定缺失时是否报错）。
     *
     * @param c        连接（可为事务内连接）
     * @param tenant   租户标识
     * @param digest   release 内容摘要
     * @param required true 表示不存在即抛 RELEASE_NOT_FOUND
     * @return release 行；required 为 false 且不存在时返回 null
     * @throws SQLException   查询失败
     * @throws StoreException RELEASE_NOT_FOUND（required 为 true 且行不存在）
     */
    public JSONObject release(Connection c, String tenant, String digest, boolean required) throws SQLException {
        JSONObject row = first(rows(c, "SELECT * FROM harness_release WHERE tenant=? AND digest=?" + lock(), id(tenant), RuntimeSupport.digest(digest)));
        if (row == null && required) {
            throw new StoreException("RELEASE_NOT_FOUND");
        }
        return row;
    }

    /**
     * 在独立事务中查询单个 release。
     *
     * @param tenant 租户标识
     * @param digest release 内容摘要
     * @return release 行
     * @throws StoreException RELEASE_NOT_FOUND（不存在）
     */
    public JSONObject release(String tenant, String digest) {
        return store.tx(c -> release(c, tenant, digest, true));
    }

    /**
     * 列出租户下全部 release 的摘要列。
     *
     * @param tenant 租户标识
     * @return 按 skill_name,digest 排序的 (digest,skill_name,state,version) 行数组
     */
    public JSONArray releases(String tenant) {
        return store.tx(c -> rows(c, "SELECT digest,skill_name,state,version FROM harness_release WHERE tenant=? ORDER BY skill_name,digest", id(tenant)));
    }

    /**
     * release 生命周期流转：ready/draining/retired，带乐观版本校验。
     *
     * <p>retire 前检查仍被未退役 release 的 dependencies 引用、
     * harness_release_ref 或 skill binding 引用，任一存在即拒绝。
     *
     * @param tenant   租户标识
     * @param digest   release 内容摘要
     * @param expected 调用方期望的当前 version，不匹配即 VERSION_CONFLICT
     * @param state    目标状态，仅接受 ready/draining/retired
     * @return 流转后的 release 行
     * @throws StoreException INVALID_RELEASE_STATE（非法目标状态）、
     *                        RELEASE_NOT_FOUND（release 不存在）、VERSION_CONFLICT（版本不匹配）、
     *                        RELEASE_RETIRED（已退役不可再流转）、RELEASE_REFERENCED（退役时仍被引用）
     */
    public JSONObject lifecycle(String tenant, String digest, long expected, String state) {
        if (!Arrays.asList("ready", "draining", "retired").contains(state)) {
            throw new StoreException("INVALID_RELEASE_STATE");
        }
        return store.tx(c -> {
            JSONObject old = release(c, tenant, digest, true);
            if (old.getLong("version") != expected) {
                throw new StoreException("VERSION_CONFLICT");
            }
            if (old.getString("state").equals("retired")) {
                throw new StoreException("RELEASE_RETIRED");
            }
            if (state.equals("retired")) {
                JSONArray dependents = rows(c, "SELECT provenance_json FROM harness_release WHERE tenant=? AND state<>'retired'", tenant);
                for (int i = 0; i < dependents.length(); i++) {
                    JSONObject provenance = new JSONObject(dependents.getJSONObject(i).getString("provenance_json"));
                    if (RuntimeSupport.strings(provenance.getJSONArray("dependencies")).contains(digest)) {
                        throw new StoreException("RELEASE_REFERENCED");
                    }
                }
                if (first(rows(c, "SELECT run_id FROM harness_release_ref WHERE tenant=? AND digest=?", tenant, digest)) != null
                        || first(rows(c, "SELECT target_ref FROM harness_binding WHERE tenant=? AND binding_kind='skill' AND target_ref=?", tenant, digest)) != null) {
                    throw new StoreException("RELEASE_REFERENCED");
                }
            }
            if (update(c, "UPDATE harness_release SET state=?,version=version+1 WHERE tenant=? AND digest=? AND version=?", state, tenant, digest, expected) != 1) {
                throw new StoreException("VERSION_CONFLICT");
            }
            return release(c, tenant, digest, true);
        });
    }

    /**
     * 建立或更新 binding（skill 绑定 release，task 绑定 profile）。
     *
     * <p>skill 绑定要求 release 的 skill_name 与 name 一致且状态 ready；
     * task 绑定要求 target 为 name@version 且对应 profile 已存在。
     * 写入带乐观版本：expected 为 0 表示新建。
     *
     * @param tenant   租户标识
     * @param kind     绑定类型，仅接受 "skill" 或 "policy"
     * @param name     绑定名称
     * @param target   绑定目标（skill 为 release digest，task 为 profile 引用）
     * @param expected 期望的当前 version，0 表示期望不存在（新建）
     * @return 包含 target 与新 version 的 JSON
     * @throws StoreException INVALID_BINDING_KIND（非法绑定类型）、
     *                        RELEASE_NOT_READY（skill 目标未就绪）、INVALID_PROFILE_REF（policy 引用格式非法）、
     *                        PROFILE_NOT_FOUND（policy 目标不存在）、VERSION_CONFLICT（版本不匹配）
     */
    public JSONObject bind(String tenant, String kind, String name, String target, long expected) {
        if (!Arrays.asList("skill", "policy").contains(kind)) {
            throw new StoreException("INVALID_BINDING_KIND");
        }
        return store.tx(c -> {
            if (kind.equals("skill")) {
                JSONObject release = release(c, tenant, target, true);
                if (!release.getString("skill_name").equals(name) || !release.getString("state").equals("ready")) {
                    throw new StoreException("RELEASE_NOT_READY");
                }
            } else {
                String[] ref = target.split("@", -1);
                if (ref.length != 2 || !ref[0].equals(name)) {
                    throw new StoreException("INVALID_PROFILE_REF");
                }
                if (kind.equals("policy") && !name.equals("default")) throw new StoreException("INVALID_PROFILE_REF");
                profile(c, tenant, kind, ref[0], ref[1]);
            }
            JSONObject old = first(rows(c, "SELECT * FROM harness_binding WHERE tenant=? AND binding_kind=? AND binding_name=?" + lock(), id(tenant), kind, name));
            long v = old == null ? 0 : old.getLong("version");
            if (v != expected) {
                throw new StoreException("VERSION_CONFLICT");
            }
            if (old == null) {
                update(c, "INSERT INTO harness_binding VALUES (?,?,?,?,1)", tenant, kind, name, target);
            } else if (update(c, "UPDATE harness_binding SET target_ref=?,version=version+1 WHERE tenant=? AND binding_kind=? AND binding_name=? AND version=?", target, tenant, kind, name, expected) != 1) {
                throw new StoreException("VERSION_CONFLICT");
            }
            return new JSONObject().put("target", target).put("version", v + 1);
        });
    }

    /**
     * 查询当前生效的 binding（连接级，可加入调用方事务）。
     *
     * @param c      连接
     * @param tenant 租户标识
     * @param kind   绑定类型（skill/policy）
     * @param name   绑定名称
     * @return 包含 target_ref 与 version 的行
     * @throws SQLException   查询失败
     * @throws StoreException BINDING_NOT_FOUND（binding 不存在）
     */
    public JSONObject active(Connection c, String tenant, String kind, String name) throws SQLException {
        JSONObject row = first(rows(c, "SELECT target_ref,version FROM harness_binding WHERE tenant=? AND binding_kind=? AND binding_name=?" + lock(), id(tenant), kind, name));
        if (row == null) {
            throw new StoreException("BINDING_NOT_FOUND");
        }
        return row;
    }

    /**
     * 登记不可变 profile；同键同摘要重复登记返回原定义（幂等）。
     *
     * @param tenant     租户标识
     * @param definition profile 定义 JSON，必须包含 kind、id、version 字段
     * @return 入库或既有的 profile 定义
     * @throws StoreException IMMUTABLE_PROFILE_CONFLICT（同键但摘要不同，违反不可变性）
     */
    public JSONObject putProfile(String tenant, JSONObject definition) {
        RuntimeSupport.validate("tenant-policy", definition);
        String kind = definition.getString("kind"), name = id(definition.getString("id")), version = id(definition.getString("version")), digest = hash(definition);
        return store.tx(c -> {
            JSONObject old = first(rows(c, "SELECT digest FROM harness_profile WHERE tenant=? AND profile_kind=? AND profile_id=? AND profile_version=?" + lock(), id(tenant), kind, name, version));
            if (old != null) {
                if (!old.getString("digest").equals(digest)) {
                    throw new StoreException("IMMUTABLE_PROFILE_CONFLICT");
                }
                return definition;
            }
            update(c, "INSERT INTO harness_profile VALUES (?,?,?,?,?,?)", tenant, kind, name, version, digest, canonical(definition));
            return definition;
        });
    }

    /**
     * 按 (kind, id, version) 查询 profile 定义（连接级）。
     *
     * @param c       连接
     * @param tenant  租户标识
     * @param kind    profile 类型
     * @param name    profile ID
     * @param version profile 版本
     * @return profile 定义 JSON（由 definition_json 解析）
     * @throws SQLException   查询失败
     * @throws StoreException PROFILE_NOT_FOUND（profile 不存在）
     */
    public JSONObject profile(Connection c, String tenant, String kind, String name, String version) throws SQLException {
        JSONObject row = first(rows(c, "SELECT definition_json FROM harness_profile WHERE tenant=? AND profile_kind=? AND profile_id=? AND profile_version=?" + lock(), id(tenant), id(kind), id(name), id(version)));
        if (row == null) {
            throw new StoreException("PROFILE_NOT_FOUND");
        }
        return new JSONObject(row.getString("definition_json"));
    }

    /**
     * 在独立事务中按 (kind, id, version) 查询 profile 定义。
     *
     * @param tenant  租户标识
     * @param kind    profile 类型
     * @param name    profile ID
     * @param version profile 版本
     * @return profile 定义 JSON
     * @throws StoreException PROFILE_NOT_FOUND（profile 不存在）
     */
    public JSONObject profile(String tenant, String kind, String name, String version) {
        return store.tx(c -> profile(c, tenant, kind, name, version));
    }

    /**
     * 创建 run：由 resolver 在事务内解析权威输入后走 RunStore 的创建流程。
     *
     * @param tenant   租户标识
     * @param subject  主体标识
     * @param key      幂等键
     * @param taskId   服务端幂等命名空间
     * @param request  请求 JSON
     * @param resolver 权威输入解析回调
     * @return 创建（或幂等重放）得到的 run 行
     */
    public JSONObject createRun(String tenant, String subject, String key, String taskId, JSONObject request, Resolver resolver) {
        return store.createResolved(tenant, subject, key, taskId, request, resolver::resolve);
    }

    /**
     * 读取 run 的固定配置快照并做完整性校验（连接级）。
     *
     * @param c       连接
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param runId   run ID
     * @return 固定的配置 JSON
     * @throws SQLException   查询失败
     * @throws StoreException RUN_CONFIG_NOT_PINNED（配置未固定）、
     *                        RUN_CONFIG_CORRUPT（快照摘要与登记摘要不符）
     */
    public JSONObject pinned(Connection c, String tenant, String subject, String runId) throws SQLException {
        store.run(c, tenant, subject, runId, true);
        JSONObject row = first(rows(c, "SELECT config_json,digest FROM harness_run_config WHERE tenant=? AND subject_id=? AND run_id=?", tenant, subject, runId));
        if (row == null) {
            throw new StoreException("RUN_CONFIG_NOT_PINNED");
        }
        JSONObject value = new JSONObject(row.getString("config_json"));
        if (!hash(value).equals(row.getString("digest"))) {
            throw new StoreException("RUN_CONFIG_CORRUPT");
        }
        return value;
    }

    /**
     * 在独立事务中读取 run 的固定配置快照。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param runId   run ID
     * @return 固定的配置 JSON
     * @throws StoreException RUN_CONFIG_NOT_PINNED、RUN_CONFIG_CORRUPT（见连接级重载）
     */
    public JSONObject pinned(String tenant, String subject, String runId) {
        return store.tx(c -> pinned(c, tenant, subject, runId));
    }

    /**
     * 列出 run 当前已激活的 skill digest。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param runId   run ID
     * @return 按 digest 排序的已激活 skill 摘要行数组
     * @throws StoreException RUN_CONFIG_NOT_PINNED、RUN_CONFIG_CORRUPT（固定配置缺失或损坏）
     */
    public JSONArray activeSkills(String tenant, String subject, String runId) {
        return store.tx(c -> {
            pinned(c, tenant, subject, runId);
            return rows(c, "SELECT digest FROM harness_run_skill WHERE run_id=? ORDER BY digest", runId);
        });
    }

    /**
     * 激活 run 的一个 skill（无租约校验的重载）。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param runId   run ID
     * @param digest  待激活的 release 摘要
     */
    public void activate(String tenant, String subject, String runId, String digest) {
        activate(tenant, subject, runId, digest, null);
    }

    /**
     * 激活 run 的一个 skill（带租约校验的重载）。
     *
     * @param lease  调用方租约，用于属主/fence 校验
     * @param digest 待激活的 release 摘要
     */
    public void activate(JdbcRunStore.Lease lease, String digest) {
        activate(lease.tenant, lease.subject, lease.runId, digest, lease);
    }

    /**
     * 激活 skill：要求固定配置的 releases 含该 digest 且权限含
     * "skill.activate"；重复激活幂等，首次激活发射 skill.activated 事件。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param runId   run ID
     * @param digest  待激活的 release 摘要
     * @param lease   调用方租约；null 表示跳过属主校验
     * @throws StoreException RELEASE_NOT_FOUND（run 不存在）、STALE_OWNER（租约失效）、
     *                        RUN_CONFIG_NOT_PINNED、RUN_CONFIG_CORRUPT、RESOURCE_FORBIDDEN
     *                        （release 不在固定配置内或缺少 skill.activate 权限）
     */
    private void activate(String tenant, String subject, String runId, String digest, JdbcRunStore.Lease lease) {
        store.tx(c -> {
            JSONObject run = store.run(c, tenant, subject, runId, true);
            if (lease != null) {
                store.owner(c, run, lease);
            }
            JSONObject pin = pinned(c, tenant, subject, runId);
            requireRelease(pin, digest);
            cube.service.harness.runtime.RunSelection.requireTool(pin, "skill.activate");
            if (!RuntimeSupport.strings(pin.getJSONArray("permissions")).contains("skill.activate")) {
                throw new StoreException("RESOURCE_FORBIDDEN");
            }
            if (first(rows(c, "SELECT digest FROM harness_run_skill WHERE run_id=? AND digest=?", runId, digest)) == null) {
                update(c, "INSERT INTO harness_run_skill VALUES (?,?)", runId, digest);
                store.emit(c, run, "skill.activated", new JSONObject().put("digest", digest), "coordinator");
            }
            return null;
        });
    }

    /**
     * 在固定配置的 releases 列表中查找指定 digest 的条目。
     *
     * @param pin    run 的固定配置 JSON，必须包含 releases 数组
     * @param digest 目标 release 摘要
     * @return 匹配的 release 条目
     * @throws StoreException RESOURCE_FORBIDDEN（固定配置中不含该 digest）
     */
    public static JSONObject requireRelease(JSONObject pin, String digest) {
        JSONArray releases = pin.getJSONArray("releases");
        for (int i = 0; i < releases.length(); i++) {
            if (releases.getJSONObject(i).getString("digest").equals(digest)) {
                return releases.getJSONObject(i);
            }
        }
        throw new StoreException("RESOURCE_FORBIDDEN");
    }

    /**
     * 领取（新建或续租）run 的 workspace 行。
     *
     * <p>租期下限 100ms、上限 60000ms；expires_at 取 now+duration 与
     * run.lease_until 的较小值，容量配额取自固定配置的 runtime 段。
     *
     * @param lease    调用方租约，用于属主/fence 校验
     * @param duration 申请租期（毫秒），必须在 100..60000 之间
     * @return 领取后的 workspace 行
     * @throws StoreException INVALID_LEASE_DURATION（租期越界）、STALE_OWNER（租约失效）、
     *                        RUN_CONFIG_NOT_PINNED、RUN_CONFIG_CORRUPT
     */
    public JSONObject workspace(JdbcRunStore.Lease lease, long duration) {
        if (duration < 100 || duration > 60000) {
            throw new StoreException("INVALID_LEASE_DURATION");
        }
        return store.tx(c -> {
            JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, r, lease);
            JSONObject pin = pinned(c, lease.tenant, lease.subject, lease.runId), runtime = pin.getJSONObject("runtime");
            long until = Math.min(store.now(c) + duration, r.getLong("lease_until"));
            JSONObject old = first(rows(c, "SELECT * FROM harness_workspace WHERE run_id=?" + lock(), lease.runId));
            String workspace = hash(new JSONArray().put(lease.tenant).put(lease.subject).put(lease.runId));
            if (old == null) {
                update(c, "INSERT INTO harness_workspace VALUES (?,?,?,?,?,?,?,?,?,0)", lease.runId, workspace, lease.tenant, lease.subject, lease.owner, lease.fence, until, runtime.getLong("maxWorkspaceBytes"), runtime.getInt("maxWorkspaceFiles"));
            } else {
                update(c, "UPDATE harness_workspace SET owner_id=?,fence=?,expires_at=?,released=0 WHERE run_id=?", lease.owner, lease.fence, until, lease.runId);
            }
            return first(rows(c, "SELECT * FROM harness_workspace WHERE run_id=?", lease.runId));
        });
    }

    /**
     * 校验 workspace 租约后执行动作（连接仍在同一事务内）。
     *
     * <p>校验项：行存在、released=0、fence 与 owner 匹配、未过期，
     * 任一不满足即 STALE_WORKSPACE_LEASE。
     *
     * @param lease  调用方租约
     * @param action 校验通过后执行的动作回调
     * @param <T>    动作返回值类型
     * @return 动作返回值
     * @throws StoreException STALE_WORKSPACE_LEASE（workspace 租约失效）、
     *                        STALE_OWNER（run 租约失效）及 run 查询相关错误
     */
    public <T> T workspaceAction(JdbcRunStore.Lease lease, WorkspaceAction<T> action) {
        return store.tx(c -> {
            JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, r, lease);
            JSONObject row = first(rows(c, "SELECT * FROM harness_workspace WHERE run_id=?" + lock(), lease.runId));
            if (row == null || row.getInt("released") != 0 || row.getLong("fence") != lease.fence || !row.getString("owner_id").equals(lease.owner) || row.getLong("expires_at") <= store.now(c)) {
                throw new StoreException("STALE_WORKSPACE_LEASE");
            }
            return action.apply(row);
        });
    }

    /**
     * 释放 workspace：要求 run 执行账已确认结算（execution_settlement=confirmed）。
     *
     * @param lease 调用方租约
     * @throws StoreException WORKSPACE_EXECUTION_UNSETTLED（执行未结算）、
     *                        STALE_OWNER（租约失效）
     */
    public void releaseWorkspace(JdbcRunStore.Lease lease) {
        store.tx(c -> {
            JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, r, lease);
            if (!r.getString("execution_settlement").equals("confirmed")) {
                throw new StoreException("WORKSPACE_EXECUTION_UNSETTLED");
            }
            update(c, "UPDATE harness_workspace SET released=1 WHERE run_id=? AND fence=?", lease.runId, lease.fence);
            return null;
        });
    }

    /**
     * 保存 context 检查点快照并推进 run 版本。
     *
     * <p>快照序列化后不得超过 2 MiB（2097152 字节）；runId 与 configDigest
     * 必须与当前租约及固定配置一致；同 (digest, run_id) 重复保存幂等返回
     * 既有 runVersion，否则版本 expected→expected+1 并发射
     * context.checkpoint 事件。
     *
     * @param lease    调用方租约
     * @param expected 期望的当前 run 版本
     * @param snapshot 快照 JSON，必须包含 runId、configDigest、basisVersion、policyVersion 字段
     * @return 包含 digest 与新 runVersion 的 JSON
     * @throws StoreException CONTEXT_SNAPSHOT_TOO_LARGE（超过 2 MiB）、
     *                        CONTEXT_BINDING_MISMATCH（runId 或 configDigest 不匹配）、
     *                        CONTEXT_STALE_BASIS（basisVersion 与期望版本不符）、
     *                        RUN_CONFIG_NOT_PINNED、RUN_CONFIG_CORRUPT、STALE_OWNER
     */
    public JSONObject saveContext(JdbcRunStore.Lease lease, long expected, JSONObject snapshot) {
        String digest = hash(snapshot);
        if (RuntimeSupport.bytes(snapshot).length > 2097152) {
            throw new StoreException("CONTEXT_SNAPSHOT_TOO_LARGE");
        }
        return store.tx(c -> {
            JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, r, lease);
            JSONObject pin = pinned(c, lease.tenant, lease.subject, lease.runId);
            if (!snapshot.getString("runId").equals(lease.runId) || !snapshot.getString("configDigest").equals(hash(pin))) {
                throw new StoreException("CONTEXT_BINDING_MISMATCH");
            }
            JSONObject old = first(rows(c, "SELECT digest,run_version FROM harness_context WHERE digest=? AND run_id=?", digest, lease.runId));
            if (old != null) {
                return new JSONObject().put("digest", digest).put("runVersion", old.getLong("run_version"));
            }
            store.version(r, expected);
            if (snapshot.getLong("basisVersion") != expected) {
                throw new StoreException("CONTEXT_STALE_BASIS");
            }
            update(c, "INSERT INTO harness_context VALUES (?,?,?,?,?,?)", digest, lease.runId, lease.tenant, lease.subject, expected + 1, canonical(snapshot));
            store.emit(c, r, "context.checkpoint", new JSONObject().put("digest", digest).put("policyVersion", snapshot.getString("policyVersion")), "coordinator");
            return new JSONObject().put("digest", digest).put("runVersion", expected + 1);
        });
    }

    /**
     * 单事务读取权威输入、意图顺序与执行证据，构成 context 重建基准。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param runId   run ID
     * @return 包含 version、pin、events、rounds、calls 的基准 JSON
     */
    public JSONObject contextBasis(String tenant, String subject, String runId) {
        return store.tx(c -> {
            JSONObject r = store.run(c, tenant, subject, runId, true), pin = pinned(c, tenant, subject, runId);
            return new JSONObject().put("version", r.getLong("version")).put("pin", pin)
                    .put("events", rows(c, "SELECT event_seq,event_type,data_json FROM harness_event WHERE run_id=? ORDER BY event_seq", runId))
                    .put("rounds", rows(c, "SELECT * FROM harness_round WHERE run_id=? ORDER BY intent_id,attempt", runId))
                    .put("calls", rows(c, "SELECT * FROM harness_tool_call WHERE run_id=? ORDER BY intent_id,attempt", runId));
        });
    }

    /**
     * 按摘要读取 context 快照并做完整性校验。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param runId   run ID
     * @param digest  快照摘要
     * @return 快照 JSON
     * @throws StoreException CONTEXT_NOT_FOUND（快照不存在）、
     *                        CONTEXT_CORRUPT（快照摘要与登记摘要不符）、
     *                        RUN_CONFIG_NOT_PINNED、RUN_CONFIG_CORRUPT
     */
    public JSONObject context(String tenant, String subject, String runId, String digest) {
        return store.tx(c -> {
            pinned(c, tenant, subject, runId);
            JSONObject row = first(rows(c, "SELECT snapshot_json,digest FROM harness_context WHERE tenant=? AND subject_id=? AND run_id=? AND digest=?", tenant, subject, runId, digest));
            if (row == null) {
                throw new StoreException("CONTEXT_NOT_FOUND");
            }
            JSONObject value = new JSONObject(row.getString("snapshot_json"));
            if (!hash(value).equals(row.getString("digest"))) {
                throw new StoreException("CONTEXT_CORRUPT");
            }
            return value;
        });
    }

    /**
     * 取 run 最新版本（run_version 最大）的 context 快照摘要。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param runId   run ID
     * @return 最新快照的 digest
     * @throws StoreException CONTEXT_NOT_FOUND（没有任何快照）、
     *                        RUN_CONFIG_NOT_PINNED、RUN_CONFIG_CORRUPT
     */
    public String latestContext(String tenant, String subject, String runId) {
        return store.tx(c -> {
            pinned(c, tenant, subject, runId);
            JSONObject row = first(rows(c, "SELECT digest FROM harness_context WHERE run_id=? ORDER BY run_version DESC LIMIT 1", runId));
            if (row == null) {
                throw new StoreException("CONTEXT_NOT_FOUND");
            }
            return row.getString("digest");
        });
    }

    /** Read the current skill or business-policy binding. */
    public JSONObject active(String tenant, String kind, String name) {
        return store.tx(c -> active(c, tenant, kind, name));
    }

    public JSONObject tenantPolicy(String tenant) {
        return store.tx(c -> tenantPolicy(c, tenant));
    }

    public JSONObject tenantPolicy(Connection c, String tenant) throws SQLException {
        String[] ref = active(c, tenant, "policy", "default").getString("target_ref").split("@", -1);
        return profile(c, tenant, "policy", ref[0], ref[1]);
    }

    /** Called inside the serialized run-creation transaction, after idempotent replay. */
    public void checkAdmission(Connection c, String tenant, int maximum) throws SQLException {
        if (first(rows(c, "SELECT COUNT(*) AS n FROM harness_run WHERE tenant=? AND state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling')", tenant)).getLong("n") >= maximum) {
            throw new StoreException("HARNESS_BUSY");
        }
    }

}
