/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.service.harness.files.FileConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.*;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 持久文件身份、准入 pin 与带 fence 的删除认领。
 *
 * <p>本类只做数据库账本，不做任何文件系统 IO：文件身份、配额、pin、
 * 绑定与删除状态全部落库，字节流由传输层负责。每个文件身份使用独立
 * 随机 storageKey，不共享/不去重 blob；删除采用 claim/fence/CAS 与
 * 指数退避重试，跨 JVM 节点用 node 身份与策略租约区分。
 */
public final class FileStore {

    /**
     * 共享的 RunStore，提供事务边界、run 行访问与租约校验。
     */
    private final JdbcRunStore store;

    /**
     * 行锁后缀：MySQL 为 " FOR UPDATE"，SQLite 为空串（依赖 BEGIN IMMEDIATE）。
     */
    private final String lock;

    /**
     * 文件存储配置（rootId、policy、各类 TTL 与配额、扫描批量等），构造时注入。
     */
    public final FileConfig config;

    /**
     * 本节点实例身份（"file-" + 随机 UUID），用于 pin 归属、策略租约与删除认领。
     */
    public final String node = "file-" + UUID.randomUUID();

    /**
     * 绑定共享 RunStore、方言标志与文件配置。
     *
     * @param store  共享的 RunStore 实例
     * @param mysql  true 为 MySQL（行锁可用），false 为 SQLite
     * @param config 文件存储配置
     */
    public FileStore(JdbcRunStore store, boolean mysql, FileConfig config) {
        this.store = store;
        this.config = config;
        lock = mysql ? " FOR UPDATE" : "";
    }

    /**
     * 读取数据库时钟（毫秒）。
     *
     * @return 当前数据库时间戳
     */
    public long now() {
        return store.tx(store::now);
    }

    /**
     * 把本 root 绑定到实例身份；重复绑定同一身份幂等，不同身份拒绝。
     *
     * @param instance 实例身份标识
     * @throws StoreException FILE_ROOT_IDENTITY_MISMATCH（root 已绑定到其他实例）
     */
    public void bindRoot(String instance) {
        store.tx(
                c -> {
                    rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1" + lock);
                    JSONObject prior =
                            first(
                                    rows(
                                            c,
                                            "SELECT instance_id FROM harness_file_root WHERE root_id=?",
                                            config.rootId));
                    if (prior == null) {
                        update(c, "INSERT INTO harness_file_root VALUES (?,?)", config.rootId, id(instance));
                    } else if (!instance.equals(prior.getString("instance_id"))) {
                        throw new StoreException("FILE_ROOT_IDENTITY_MISMATCH");
                    }
                    return null;
                });
    }

    /**
     * 注册或续租本节点的文件策略（policy hash + 租约）。
     *
     * <p>store identity 行对注册串行化，即使策略行是新的；若其他节点持有
     * 未过期且策略不同的租约则拒绝启动/注册。租约时长取
     * max(120000, 3*scan) 毫秒。
     *
     * @throws StoreException FILE_POLICY_CONFLICT（同 root 存在异策略的活动租约）
     */
    public void policy() {
        store.tx(
                c -> {
                    long time = store.now(c);
                    // store identity 行对注册串行化，即使策略行是新的。
                    rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1" + lock);
                    if (first(
                            rows(
                                    c,
                                    "SELECT node_id FROM harness_file_policy WHERE root_id=? AND policy_hash<>? AND lease_until>?",
                                    config.rootId,
                                    config.policy,
                                    time))
                            != null) {
                        throw new StoreException("FILE_POLICY_CONFLICT");
                    }
                    if (update(
                            c,
                            "UPDATE harness_file_policy SET policy_hash=?,lease_until=? WHERE node_id=?",
                            config.policy,
                            time + Math.max(120000, 3 * config.scan),
                            node)
                            == 0) {
                        update(
                                c,
                                "INSERT INTO harness_file_policy VALUES (?,?,?,?)",
                                node,
                                config.rootId,
                                config.policy,
                                time + Math.max(120000, 3 * config.scan));
                    }
                    return null;
                });
    }

    /**
     * 释放本节点的策略租约（停机时调用，把 lease_until 置 0）。
     */
    public void close() {
        store.tx(
                c -> {
                    update(c, "UPDATE harness_file_policy SET lease_until=0 WHERE node_id=?", node);
                    return null;
                });
    }

    /**
     * 按 (file_id, tenant, subject, root) 查询文件行（连接级）。
     *
     * @param c       连接
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param id      文件 ID
     * @param locking true 时追加行锁（MySQL FOR UPDATE）
     * @return 文件行
     * @throws Exception      查询失败
     * @throws StoreException FILE_NOT_FOUND（文件不存在或不属于该租户/主体/root）
     */
    private JSONObject file(Connection c, String tenant, String subject, String id, boolean locking)
            throws Exception {
        JSONObject r =
                first(
                        rows(
                                c,
                                "SELECT * FROM harness_file WHERE file_id=? AND tenant=? AND subject_id=? AND root_id=?"
                                        + (locking ? lock : ""),
                                id(id),
                                tenant,
                                subject,
                                config.rootId));
        if (r == null) {
            throw new StoreException("FILE_NOT_FOUND");
        }
        return r;
    }

    /**
     * 在独立事务中按 ID 查询文件元数据。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param id      文件 ID
     * @return 文件行
     * @throws StoreException FILE_NOT_FOUND（文件不存在）
     */
    public JSONObject get(String tenant, String subject, String id) {
        return store.tx(c -> file(c, tenant, subject, id, false));
    }

    /**
     * 校验文件行可被读取/使用：未因 run 撤销而被禁、未过期、状态为 ready。
     *
     * @param c 连接
     * @param r 文件行（须含 run_id、expires_at、status 字段）
     * @throws Exception      查询失败
     * @throws StoreException FILE_FORBIDDEN（所属 run 已被撤销）、
     *                        FILE_EXPIRED（已过期/删除中/已删除）、FILE_NOT_READY（尚未 ready）
     */
    private void ready(Connection c, JSONObject r) throws Exception {
        if (!r.isNull("run_id")
                && first(
                rows(
                        c,
                        "SELECT run_id FROM harness_tool_revocation WHERE run_id=?",
                        r.getString("run_id")))
                != null) {
            throw new StoreException("FILE_FORBIDDEN");
        }
        if (r.getLong("expires_at") <= store.now(c)
                || Arrays.asList("expired", "delete_pending", "deleted").contains(r.getString("status"))) {
            throw new StoreException("FILE_EXPIRED");
        }
        if (!r.getString("status").equals("ready")) {
            throw new StoreException("FILE_NOT_READY");
        }
    }

    /**
     * 创建文件上传会话（无租约的重载）。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param key     幂等键
     * @param kind    文件类型（input/artifact/temp）
     * @param run     关联 run ID，可为 null
     * @param request 请求 JSON（fileName、mediaType、size、sha256）
     * @return 创建的文件行（状态 uploading）
     */
    public JSONObject create(
            String tenant, String subject, String key, String kind, String run, JSONObject request) {
        return create(tenant, subject, key, kind, run, request, null);
    }

    /**
     * 创建文件上传会话（带租约的重载，固定为 artifact 类型）。
     *
     * @param lease   调用方租约，用于属主/fence 校验
     * @param key     幂等键
     * @param request 请求 JSON（fileName、mediaType、size、sha256）
     * @return 创建的文件行（状态 uploading）
     */
    public JSONObject create(Lease lease, String key, JSONObject request) {
        return create(lease.tenant, lease.subject, key, "artifact", lease.runId, request, lease);
    }

    /**
     * 创建文件上传会话的核心实现。
     *
     * <p>按 (tenant, subject, kind, run, key) 哈希请求 ID 做幂等：同 ID
     * 同载荷返回既有文件，同 ID 异载荷报冲突。依次校验 kind、run 运行中、
     * 单文件大小与 root 配额、文件名/媒体类型格式，通过后以 uploading
     * 状态落库并记录 expires_at=now+upload。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param key     幂等键
     * @param kind    文件类型，仅接受 input/artifact/temp
     * @param run     关联 run ID，可为 null
     * @param request 请求 JSON（fileName、mediaType、size、sha256）
     * @param lease   调用方租约；null 表示跳过属主与撤销校验
     * @return 创建或幂等命中的文件行
     * @throws StoreException IDEMPOTENCY_CONFLICT（同请求 ID 不同载荷）、
     *                        INVALID_FILE_KIND（非法类型）、RUN_NOT_RUNNING（关联 run 非运行中）、
     *                        FILE_TOO_LARGE（超过单文件上限）、FILE_QUOTA_EXCEEDED（超过 root 配额）、
     *                        INVALID_FILE_METADATA（文件名或媒体类型非法）、FILE_FORBIDDEN（run 已撤销）、
     *                        STALE_OWNER（租约失效）
     */
    private JSONObject create(
            String tenant,
            String subject,
            String key,
            String kind,
            String run,
            JSONObject request,
            Lease lease) {
        String
                req =
                hash(
                        new JSONArray()
                                .put(tenant)
                                .put(subject)
                                .put(kind)
                                .put(run == null ? JSONObject.NULL : run)
                                .put(id(key))),
                digest = hash(request);
        return store.tx(
                c -> {
                    if (lease != null) {
                        store.owner(c, store.run(c, tenant, subject, run, true), lease);
                        if (first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", run))
                                != null) {
                            throw new StoreException("FILE_FORBIDDEN");
                        }
                    }
                    rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1" + lock);
                    JSONObject prior =
                            first(rows(c, "SELECT * FROM harness_file_request WHERE request_id=?", req));
                    if (prior != null) {
                        if (!digest.equals(prior.getString("payload_hash"))) {
                            throw new StoreException("IDEMPOTENCY_CONFLICT");
                        }
                        return file(c, tenant, subject, prior.getString("file_id"), false);
                    }
                    if (!Arrays.asList("input", "artifact", "temp").contains(kind)) {
                        throw new StoreException("INVALID_FILE_KIND");
                    }
                    if (run != null) {
                        JSONObject r = store.run(c, tenant, subject, run, true);
                        if (!r.getString("state").equals("running")) {
                            throw new StoreException("RUN_NOT_RUNNING");
                        }
                    }
                    long size = request.getLong("size");
                    if (size < 0 || size > config.maxFile) {
                        throw new StoreException("FILE_TOO_LARGE");
                    }
                    long reserved =
                            first(
                                    rows(
                                            c,
                                            "SELECT COALESCE(SUM(size_bytes),0) AS n FROM harness_file WHERE root_id=? AND status<>'deleted'",
                                            config.rootId))
                                    .getLong("n");
                    if (size > config.maxBytes - reserved) {
                        throw new StoreException("FILE_QUOTA_EXCEEDED");
                    }
                    String name = request.getString("fileName"), media = request.getString("mediaType");
                    if (!name.matches("[^\\\\/\\p{Cntrl}]{1,256}")
                            || name.equals(".")
                            || name.equals("..")
                            || !media.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) {
                        throw new StoreException("INVALID_FILE_METADATA");
                    }
                    String id = UUID.randomUUID().toString(),
                            storage = hash(new JSONArray().put(id).put(UUID.randomUUID().toString()));
                    long now = store.now(c);
                    update(
                            c,
                            "INSERT INTO harness_file (file_id,tenant,subject_id,kind,run_id,storage_key,root_id,file_name,media_type,size_bytes,sha256,status,created_at,expires_at,policy_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,'uploading',?,?,?)",
                            id,
                            tenant,
                            subject,
                            kind,
                            run,
                            storage,
                            config.rootId,
                            name,
                            media,
                            size,
                            request.getString("sha256"),
                            now,
                            now + config.upload,
                            config.policy);
                    update(c, "INSERT INTO harness_file_request VALUES (?,?,?)", req, digest, id);
                    return file(c, tenant, subject, id, false);
                });
    }

    /**
     * 开始写入：为上传会话取独占 pin。
     *
     * @param t   租户标识
     * @param s   主体标识
     * @param id  文件 ID
     * @param pin 调用方提供的 pin ID
     * @return 文件行
     * @throws StoreException FILE_NOT_FOUND、FILE_EXPIRED（上传窗口已过）、
     *                        FILE_UPLOAD_CLOSED（非 uploading 状态）、FILE_BUSY（已存在其他 pin）
     */
    public JSONObject beginWrite(String t, String s, String id, String pin) {
        return store.tx(
                c -> {
                    JSONObject r = file(c, t, s, id, true);
                    if (r.getLong("expires_at") <= store.now(c)) {
                        throw new StoreException("FILE_EXPIRED");
                    }
                    if (!r.getString("status").equals("uploading")) {
                        throw new StoreException("FILE_UPLOAD_CLOSED");
                    }
                    if (first(rows(c, "SELECT pin_id FROM harness_file_pin WHERE file_id=?", id)) != null) {
                        throw new StoreException("FILE_BUSY");
                    }
                    update(
                            c,
                            "INSERT INTO harness_file_pin VALUES (?,?,NULL,?,'upload',?)",
                            pin,
                            id,
                            node,
                            r.getLong("expires_at"));
                    return r;
                });
    }

    /**
     * 上报上传完成的内容摘要：必须与创建时登记的 sha256 一致。
     *
     * @param t      租户标识
     * @param s      主体标识
     * @param id     文件 ID
     * @param digest 实际上传内容的 SHA-256
     * @throws StoreException FILE_NOT_FOUND、FILE_EXPIRED（上传窗口已过或状态非法）、
     *                        FILE_DIGEST_MISMATCH（摘要不符）
     */
    public void uploaded(String t, String s, String id, String digest) {
        store.tx(
                c -> {
                    JSONObject r = file(c, t, s, id, true);
                    if (!r.getString("status").equals("uploading") || r.getLong("expires_at") <= store.now(c)) {
                        throw new StoreException("FILE_EXPIRED");
                    }
                    if (!digest.equals(r.getString("sha256"))) {
                        throw new StoreException("FILE_DIGEST_MISMATCH");
                    }
                    update(c, "UPDATE harness_file SET upload_hash=? WHERE file_id=?", digest, id);
                    return null;
                });
    }

    /**
     * 完成上传：校验摘要后置 ready 并按 kind 计算 TTL。
     *
     * <p>已 ready 的文件重复 complete 不续期（走 ready 校验后原样返回）；
     * 未完成上传（缺 upload_hash 或仍被 pin 占用）拒绝。TTL：input 取
     * config.input，temp 取 config.grace，其余（artifact）取 config.artifact。
     *
     * @param t      租户标识
     * @param s      主体标识
     * @param id     文件 ID
     * @param digest 内容 SHA-256，须与登记值一致
     * @return ready 后的文件行
     * @throws StoreException FILE_NOT_FOUND、FILE_DIGEST_MISMATCH（摘要不符）、
     *                        FILE_EXPIRED（上传窗口已过或状态非法）、FILE_UPLOAD_INCOMPLETE（上传未完成）、
     *                        FILE_FORBIDDEN（run 已撤销）、FILE_NOT_READY
     */
    public JSONObject complete(String t, String s, String id, String digest) {
        return store.tx(
                c -> {
                    JSONObject r = file(c, t, s, id, true);
                    if (!digest.equals(r.getString("sha256"))) {
                        throw new StoreException("FILE_DIGEST_MISMATCH");
                    }
                    if (r.getString("status").equals("ready")) {
                        ready(c, r);
                        return r;
                    }
                    if (r.getLong("expires_at") <= store.now(c) || !r.getString("status").equals("uploading")) {
                        throw new StoreException("FILE_EXPIRED");
                    }
                    if (!digest.equals(r.optString("upload_hash"))
                            || first(rows(c, "SELECT pin_id FROM harness_file_pin WHERE file_id=?", id)) != null) {
                        throw new StoreException("FILE_UPLOAD_INCOMPLETE");
                    }
                    long time = store.now(c),
                            ttl =
                                    r.getString("kind").equals("input")
                                            ? config.input
                                            : r.getString("kind").equals("temp") ? config.grace : config.artifact;
                    update(
                            c,
                            "UPDATE harness_file SET status='ready',ready_at=?,expires_at=?,policy_hash=? WHERE file_id=?",
                            time,
                            time + ttl,
                            config.policy,
                            id);
                    return file(c, t, s, id, false);
                });
    }

    /**
     * 释放本节点持有的 pin。
     *
     * @param pin pin ID；仅允许删除 owner 为本节点的 pin
     */
    public void unpin(String pin) {
        store.tx(
                c -> {
                    update(c, "DELETE FROM harness_file_pin WHERE pin_id=? AND owner_id=?", pin, node);
                    return null;
                });
    }

    /**
     * 为使用目的 pin 一个文件（幂等/冲突校验 + 准入检查）。
     *
     * <p>同 pin ID 重复请求须 kind/run/file 一致才幂等返回；consume 类
     * 可将所有权转移到本节点并再次校验 run 撤销。新 pin 要求文件 ready、
     * run 运行中且已绑定；download 类 pin 带 now+download 租约，其余
     * 类不过期（Long.MAX_VALUE）。
     *
     * @param t    租户标识
     * @param s    主体标识
     * @param id   文件 ID
     * @param pin  pin ID
     * @param run  关联 run ID，可为 null
     * @param kind pin 类型（upload/download/consume）
     * @return 文件行
     * @throws StoreException FILE_NOT_FOUND、FILE_PIN_CONFLICT（同 pin 参数不一致）、
     *                        FILE_FORBIDDEN（run 已撤销）、FILE_EXPIRED/FILE_NOT_READY（ready 校验失败）、
     *                        RUN_NOT_RUNNING、FILE_NOT_BOUND（文件未绑定到该 run）
     */
    public JSONObject pin(String t, String s, String id, String pin, String run, String kind) {
        return store.tx(
                c -> {
                    JSONObject r = file(c, t, s, id, true);
                    JSONObject old = first(rows(c, "SELECT * FROM harness_file_pin WHERE pin_id=?", pin));
                    if (old != null) {
                        if (!kind.equals(old.getString("kind"))
                                || !Objects.equals(
                                run, old.opt("run_id") == JSONObject.NULL ? null : old.optString("run_id"))
                                || !id.equals(old.getString("file_id"))
                                || (!node.equals(old.getString("owner_id")) && !kind.equals("consume"))) {
                            throw new StoreException("FILE_PIN_CONFLICT");
                        }
                        if (kind.equals("consume")) {
                            if (first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", run))
                                    != null) {
                                throw new StoreException("FILE_FORBIDDEN");
                            }
                            update(c, "UPDATE harness_file_pin SET owner_id=? WHERE pin_id=?", node, pin);
                        }
                        return r;
                    }
                    ready(c, r);
                    if (run != null) {
                        JSONObject rr = store.run(c, t, s, run, true);
                        if (!rr.getString("state").equals("running")) {
                            throw new StoreException("RUN_NOT_RUNNING");
                        }
                        if (first(
                                rows(
                                        c,
                                        "SELECT run_id FROM harness_file_binding WHERE run_id=? AND file_id=?",
                                        run,
                                        id))
                                == null) {
                            throw new StoreException("FILE_NOT_BOUND");
                        }
                    }
                    update(
                            c,
                            "INSERT INTO harness_file_pin VALUES (?,?,?,?,?,?)",
                            pin,
                            id,
                            run,
                            node,
                            kind,
                            kind.equals("download") ? store.now(c) + config.download : Long.MAX_VALUE);
                    return r;
                });
    }

    /**
     * 校验附件 ID 集合：无重复、均存在且为 ready 的 input 文件。
     *
     * @param c   连接（调用方事务内）
     * @param t   租户标识
     * @param s   主体标识
     * @param ids 附件文件 ID 数组
     * @throws Exception      查询失败
     * @throws StoreException DUPLICATE_INPUT（集合内有重复 ID）、FILE_NOT_FOUND、
     *                        FILE_EXPIRED/FILE_NOT_READY、INPUT_REQUIRED（存在非 input 类型文件）
     */
    public void validateBindings(Connection c, String t, String s, JSONArray ids) throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            if (!seen.add(id)) {
                throw new StoreException("DUPLICATE_INPUT");
            }
            JSONObject r = file(c, t, s, id, true);
            ready(c, r);
            if (!r.getString("kind").equals("input")) {
                throw new StoreException("INPUT_REQUIRED");
            }
        }
    }

    /**
     * 把附件绑定到 run（缺失的绑定关系才插入，幂等）。
     *
     * @param c   连接（调用方事务内）
     * @param t   租户标识
     * @param s   主体标识
     * @param run run ID
     * @param ids 附件文件 ID 数组
     * @throws Exception 校验或写入失败（见 validateBindings）
     */
    public void bind(Connection c, String t, String s, String run, JSONArray ids) throws Exception {
        validateBindings(c, t, s, ids);
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            if (first(
                    rows(
                            c,
                            "SELECT run_id FROM harness_file_binding WHERE run_id=? AND file_id=?",
                            run,
                            id))
                    == null) {
                update(c, "INSERT INTO harness_file_binding VALUES (?,?)", run, id);
            }
        }
    }

    /**
     * 列出 run 产生的全部 artifact 元数据。
     *
     * @param t   租户标识
     * @param s   主体标识
     * @param run run ID
     * @return artifact 文件行数组，按 created_at,file_id 排序
     */
    public JSONArray list(String t, String s, String run) {
        return store.tx(
                c -> {
                    store.run(c, t, s, run, false);
                    return rows(
                            c,
                            "SELECT * FROM harness_file WHERE tenant=? AND subject_id=? AND run_id=? AND kind='artifact' ORDER BY created_at,file_id",
                            t,
                            s,
                            run);
                });
    }

    /**
     * 交付检查：run 的全部 artifact 必须均处于 ready。
     *
     * @param t   租户标识
     * @param s   主体标识
     * @param run run ID
     * @throws StoreException FILE_EXPIRED/FILE_NOT_READY/FILE_FORBIDDEN（任一 artifact 不可用）
     */
    public void checkDelivery(String t, String s, String run) {
        store.tx(
                c -> {
                    JSONArray all =
                            rows(
                                    c,
                                    "SELECT * FROM harness_file WHERE tenant=? AND subject_id=? AND run_id=? AND kind='artifact'",
                                    t,
                                    s,
                                    run);
                    for (int i = 0; i < all.length(); i++) {
                        ready(c, all.getJSONObject(i));
                    }
                    return null;
                });
    }

    /**
     * 取一批待清理候选文件，并把已到期的 uploading/ready 翻转为 expired。
     *
     * <p>候选条件：本 root 下已到期（uploading/ready 且 expires_at 不超过当前时间）
     * 或已进入 expired/delete_pending，且 next_attempt 已到期；批量上限
     * 为 config.batch，按 expires_at,file_id 排序。
     *
     * @return 候选文件行数组（含刚被翻转为 expired 的行）
     */
    public JSONArray candidates() {
        return store.tx(
                c -> {
                    long now = store.now(c);
                    JSONArray batch =
                            rows(
                                    c,
                                    "SELECT * FROM harness_file WHERE root_id=? AND ((status IN ('uploading','ready') AND expires_at<=?) OR status IN ('expired','delete_pending')) AND next_attempt<=? ORDER BY expires_at,file_id LIMIT "
                                            + config.batch,
                                    config.rootId,
                                    now,
                                    now);
                    for (int i = 0; i < batch.length(); i++) {
                        JSONObject r = batch.getJSONObject(i);
                        if (Arrays.asList("ready", "uploading").contains(r.getString("status"))) {
                            update(
                                    c,
                                    "UPDATE harness_file SET status='expired' WHERE file_id=? AND status IN ('ready','uploading') AND expires_at<=?",
                                    r.getString("file_id"),
                                    now);
                        }
                    }
                    return batch;
                });
    }

    /**
     * 认领一个文件的物理删除（fenced claim）。
     *
     * <p>仅认领 expired/delete_pending 且 claim/next_attempt 均已到期、
     * 无活动 pin 的文件；若文件关联 run，还要求 run 已终态且执行账已
     * 确认结算。认领成功将 status 置 delete_pending 并递增 claim_fence。
     *
     * @param id 文件 ID
     * @return 认领到的文件行（附带 status/owner_id/claim_fence/claim_until 字段）；
     * 不符合条件时返回 null
     */
    public JSONObject claim(String id) {
        return store.tx(
                c -> {
                    JSONObject r =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_file WHERE file_id=? AND root_id=?" + lock,
                                            id,
                                            config.rootId));
                    long now = store.now(c);
                    if (r == null
                            || !Arrays.asList("expired", "delete_pending").contains(r.getString("status"))
                            || r.getLong("claim_until") > now
                            || r.getLong("next_attempt") > now) {
                        return null;
                    }
                    if (first(rows(c, "SELECT pin_id FROM harness_file_pin WHERE file_id=?", id)) != null) {
                        return null;
                    }
                    String run = r.optString("run_id");
                    if (!run.isEmpty()) {
                        JSONObject rr =
                                first(
                                        rows(
                                                c,
                                                "SELECT state,execution_settlement FROM harness_run WHERE run_id=?",
                                                run));
                        if (rr != null
                                && (!Arrays.asList("succeeded", "failed", "cancelled", "timed_out")
                                .contains(rr.getString("state"))
                                || !rr.getString("execution_settlement").equals("confirmed"))) {
                            return null;
                        }
                    }
                    long fence = r.getLong("claim_fence") + 1;
                    update(
                            c,
                            "UPDATE harness_file SET status='delete_pending',owner_id=?,claim_fence=?,claim_until=? WHERE file_id=?",
                            node,
                            fence,
                            now + config.claim,
                            id);
                    return r.put("status", "delete_pending")
                            .put("owner_id", node)
                            .put("claim_fence", fence)
                            .put("claim_until", now + config.claim);
                });
    }

    /**
     * 删除前复核认领仍然有效（属主、fence、租约、状态逐项比对）。
     *
     * @param claim claim 方法返回的认领行
     * @throws StoreException STALE_FILE_CLAIM（认领已失效或被其他节点接管）
     */
    public void verifyClaim(JSONObject claim) {
        store.tx(
                c -> {
                    JSONObject r =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_file WHERE file_id=?" + lock,
                                            claim.getString("file_id")));
                    if (r == null
                            || !node.equals(r.optString("owner_id"))
                            || r.getLong("claim_fence") != claim.getLong("claim_fence")
                            || r.getLong("claim_until") <= store.now(c)
                            || !r.getString("status").equals("delete_pending")) {
                        throw new StoreException("STALE_FILE_CLAIM");
                    }
                    return null;
                });
    }

    /**
     * 标记文件已物理删除（CAS：属主、fence、状态、租约全部匹配才生效）。
     *
     * @param claim claim 方法返回的认领行
     * @throws StoreException STALE_FILE_CLAIM（CAS 失败，认领已失效）
     */
    public void deleted(JSONObject claim) {
        store.tx(
                c -> {
                    if (update(
                            c,
                            "UPDATE harness_file SET status='deleted',deleted_at=?,claim_until=0,last_error=NULL WHERE file_id=? AND owner_id=? AND claim_fence=? AND status='delete_pending' AND claim_until>?",
                            store.now(c),
                            claim.getString("file_id"),
                            node,
                            claim.getLong("claim_fence"),
                            store.now(c))
                            != 1) {
                        throw new StoreException("STALE_FILE_CLAIM");
                    }
                    return null;
                });
    }

    /**
     * 记录一次删除失败：清 claim 租约、指数退避后重试、attempts+1。
     *
     * <p>退避 = min(retryMax, retry * 2^min(20, attempts))，attempts
     * 封顶 30 次。
     *
     * @param claim claim 方法返回的认领行
     * @param code  脱敏错误码，写入 last_error
     */
    public void failed(JSONObject claim, String code) {
        store.tx(
                c -> {
                    int count = Math.min(30, claim.getInt("attempts"));
                    long delay = Math.min(config.retryMax, config.retry * (1L << Math.min(20, count)));
                    update(
                            c,
                            "UPDATE harness_file SET claim_until=0,next_attempt=?,attempts=attempts+1,last_error=? WHERE file_id=? AND owner_id=? AND claim_fence=? AND status='delete_pending'",
                            store.now(c) + delay,
                            code,
                            claim.getString("file_id"),
                            node,
                            claim.getLong("claim_fence"));
                    return null;
                });
    }

    /**
     * 崩溃/重启后的 pin 恢复：清理死亡节点的下载/上传 pin，
     * 并回收已被 confirmed 的 input.read_text 消费 pin。
     *
     * <p>仅在持有排他 OS 根锁时调用，以此证明没有旧的本机读写者存活。
     */
    public void recoverPins() {
        store.tx(
                c -> {
                    update(
                            c,
                            "DELETE FROM harness_file_pin WHERE owner_id<>? AND kind IN ('download','upload') AND file_id IN (SELECT file_id FROM harness_file WHERE root_id=?) AND owner_id NOT IN (SELECT node_id FROM harness_file_policy WHERE lease_until>?)",
                            node,
                            config.rootId,
                            store.now(c));
                    JSONArray pins =
                            rows(
                                    c,
                                    "SELECT * FROM harness_file_pin WHERE kind='consume' AND file_id IN (SELECT file_id FROM harness_file WHERE root_id=?)",
                                    config.rootId);
                    for (int i = 0; i < pins.length(); i++) {
                        JSONObject pin = pins.getJSONObject(i);
                        JSONArray calls =
                                rows(
                                        c,
                                        "SELECT * FROM harness_tool_call WHERE run_id=? AND status='confirmed'",
                                        pin.getString("run_id"));
                        for (int j = 0; j < calls.length(); j++) {
                            JSONObject call = calls.getJSONObject(j),
                                    payload = new JSONObject(call.getString("payload_json"));
                            if (payload.getString("tool").equals("input.read_text")
                                    && pin.getString("pin_id")
                                    .equals(
                                            "read-"
                                                    + hash(
                                                    new JSONArray()
                                                            .put(pin.getString("run_id"))
                                                            .put(call.getString("intent_id"))
                                                            .put(pin.getString("file_id"))))) {
                                update(c, "DELETE FROM harness_file_pin WHERE pin_id=?", pin.getString("pin_id"));
                            }
                        }
                    }
                    return null;
                });
    }

    /**
     * 校验并确认调用方对 run 的租约属主。
     *
     * @param l 调用方租约
     * @throws StoreException RELEASE_NOT_FOUND（run 不存在）、STALE_OWNER（租约失效）
     */
    public void owner(Lease l) {
        store.tx(
                c -> {
                    store.owner(c, store.run(c, l.tenant, l.subject, l.runId, true), l);
                    return null;
                });
    }

    /**
     * 汇总本 root 的文件指标：状态分布、pin 分布与近期失败清单。
     *
     * @return 包含 states、pins、failures 三组的指标 JSON
     */
    public JSONObject metrics() {
        return store.tx(
                c ->
                        new JSONObject()
                                .put(
                                        "states",
                                        rows(
                                                c,
                                                "SELECT status,COUNT(*) AS count,COALESCE(SUM(size_bytes),0) AS bytes FROM harness_file WHERE root_id=? GROUP BY status",
                                                config.rootId))
                                .put(
                                        "pins",
                                        rows(
                                                c,
                                                "SELECT kind,COUNT(*) AS count FROM harness_file_pin WHERE file_id IN (SELECT file_id FROM harness_file WHERE root_id=?) GROUP BY kind",
                                                config.rootId))
                                .put(
                                        "failures",
                                        rows(
                                                c,
                                                "SELECT file_id,attempts,last_error,next_attempt FROM harness_file WHERE root_id=? AND last_error IS NOT NULL LIMIT 100",
                                                config.rootId)));
    }
}
