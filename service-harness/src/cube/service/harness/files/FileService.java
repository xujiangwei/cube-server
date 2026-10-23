/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.files;

import com.sun.net.httpserver.HttpExchange;
import cube.service.harness.runtime.AssetFiles;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.FileStore;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreConfig;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 专用本地文件适配器：把 input/artifact 的上传、下载、元数据、消费与物理清理
 * 串成一条链路，账本（FileStore）与磁盘（AssetFiles）严格分离。
 *
 * <p>并发模型：跨 JVM 的 OS 根锁保护真实句柄与路径，锁不可用时以 "FILE_BUSY"
 * 快速失败，绝不把 API/协调线程停在流上；进程内另有 2 个传输许可由上传与下载
 * 共用。业务身份只来自可信 {@link ProfileRegistry.Identity}，不能从载荷取得
 * tenant/subject。上传与下载都带 SHA-256 摘要、大小与期限校验，文件 ID 只映射
 * 到 root 下 objects/&lt;storageKey&gt; 路径，绝不当宿主路径使用。
 */
public final class FileService implements AutoCloseable {

    /**
     * 文件账本：身份、pin、claim 与指标的持久化视图，自身不做文件系统 I/O。
     */
    public final FileStore ledger;

    /**
     * 校验并换算后的文件配置快照（毫秒制）。
     */
    public final FileConfig config;

    /**
     * 磁盘根：跨 JVM 根锁、配额与对象存储（objects/&lt;storageKey&gt;）。
     */
    private final AssetFiles disk;

    /**
     * 可信业务身份；进程内可信入口省略身份参数时使用，缺失即失败。
     */
    private final ProfileRegistry.Identity identity;

    /**
     * 守护定时线程池（harness-files，2 线程）：驱动清理扫描与上传截止定时器。
     */
    private final ScheduledExecutorService timer =
            Executors.newScheduledThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "harness-files");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * 并发传输信号量（2 个许可）：上传与下载共用，满时快速失败 "FILE_BUSY"。
     */
    private final Semaphore transfers = new Semaphore(2);

    /**
     * 最近一次清理扫描失败的错误码（volatile）；成功扫描置 null，由 metrics 暴露。
     */
    private volatile String lastError;

    /**
     * 下载句柄 HMAC-SHA256 密钥（files.download.key，至少 32 字符）。
     */
    private final String handleKey;

    /**
     * 构造文件适配器并完成 root 绑定：校验配置与密钥、确认文件 root 与执行仓库
     * 目录互不嵌套、写入/核对磁盘随机身份标记、绑定账本 root 归属并恢复 pin。
     *
     * @param store    JDBC RunStore（账本数据源）
     * @param p        配置源
     * @param identity 可信业务身份
     * @throws Exception 配置或密钥非法（FILE_HANDLE_KEY_REQUIRED）、root 不安全或与仓库
     *                   交叉（FILE_ROOT_MUST_BE_INDEPENDENT）、身份标记冲突
     *                   （FILE_ROOT_IDENTITY_MISMATCH）、策略冲突或恢复失败
     */
    public FileService(JdbcRunStore store, Properties p, ProfileRegistry.Identity identity)
            throws Exception {
        this.identity = identity;
        config = new FileConfig(p);
        handleKey = p.getProperty("files.download.key", "");
        if (handleKey.length() < 32) {
            throw new IllegalArgumentException("FILE_HANDLE_KEY_REQUIRED");
        }
        Path root = Paths.get(Objects.requireNonNull(p.getProperty("files.root"), "files.root"));
        Path repository = Paths.get(p.getProperty("execution.repository")).toAbsolutePath().normalize();
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.startsWith(repository) || repository.startsWith(normalized)) {
            throw new IllegalArgumentException("FILE_ROOT_MUST_BE_INDEPENDENT");
        }
        disk =
                new AssetFiles(
                        root,
                        config.maxBytes,
                        JdbcRunStore.hash(
                                new JSONArray()
                                        .put("files-v1")
                                        .put(p.getProperty("store.identity"))
                                        .put(config.rootId)));
        ledger = new FileStore(store, new StoreConfig(p).mysql(), config);
        disk.tryLocked(
                () -> {
                    // 磁盘随机身份标记：首次启动创建，之后每次启动核对归属。
                    Path marker = disk.root.resolve(".harness-file-identity");
                    if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                                || Files.isSymbolicLink(marker)
                                || !marker.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(marker.toRealPath())
                                || Files.size(marker) > 128) {
                            throw new StoreException("FILE_ROOT_IDENTITY_MISMATCH");
                        }
                    } else {
                        Files.write(
                                marker,
                                UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII),
                                StandardOpenOption.CREATE_NEW);
                    }
                    ledger.bindRoot(new String(Files.readAllBytes(marker), StandardCharsets.US_ASCII));
                    ledger.policy();
                    ledger.recoverPins();
                    return null;
                });
    }

    /**
     * 启动周期性清理扫描：立即执行一次，之后按 config.scan 固定间隔重跑。
     * 单次失败只记录错误码（StoreException 取其 code，其余记 FILE_CLEANUP_FAILED），
     * 成功后清除上次错误，不中断后续扫描。
     */
    public void start() {
        timer.scheduleWithFixedDelay(
                () -> {
                    try {
                        scan();
                        lastError = null;
                    } catch (Exception e) {
                        lastError =
                                e instanceof StoreException ? ((StoreException) e).code : "FILE_CLEANUP_FAILED";
                    }
                },
                0,
                config.scan,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 把账本行解析为磁盘对象路径：objects/&lt;storageKey&gt;&lt;suffix&gt;，
     * 并确认该行属于当前 root。
     *
     * @param row    账本文件行（含 root_id 与 storage_key）
     * @param suffix 对象后缀（".part" 临时文件 / ".data" 正式内容）
     * @return root 下归一化后的对象路径
     * @throws Exception root 不匹配（FILE_ROOT_IDENTITY_MISMATCH）或路径不安全
     */
    private Path path(JSONObject row, String suffix) throws Exception {
        if (!config.rootId.equals(row.getString("root_id"))) {
            throw new StoreException("FILE_ROOT_IDENTITY_MISMATCH");
        }
        return RuntimeSupport.resolve(
                disk.root, "objects/" + RuntimeSupport.digest(row.getString("storage_key")) + suffix, true);
    }

    /**
     * 以可信身份创建 input 文件上传会话。
     *
     * @param key 幂等键
     * @param req 创建请求（fileName/mediaType/size/sha256）
     * @return 文件视图（state=uploading）
     * @throws NullPointerException 适配器未绑定可信身份（TRUSTED_IDENTITY_REQUIRED）
     * @throws StoreException       元数据非法（INVALID_FILE_METADATA）、超限
     *                              （FILE_TOO_LARGE/FILE_QUOTA_EXCEEDED）或同键异载荷（IDEMPOTENCY_CONFLICT）
     */
    public JSONObject create(String key, JSONObject req) {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        return create(identity, key, req);
    }

    /**
     * 以指定身份创建 input 文件上传会话；先刷新策略租约再落账。
     *
     * @param identity 业务身份（tenant/subject 隔离）
     * @param key      幂等键
     * @param req      创建请求（fileName/mediaType/size/sha256）
     * @return 文件视图（state=uploading）
     * @throws StoreException 元数据非法（INVALID_FILE_METADATA）、超限
     *                        （FILE_TOO_LARGE/FILE_QUOTA_EXCEEDED）或同键异载荷（IDEMPOTENCY_CONFLICT）
     */
    public JSONObject create(ProfileRegistry.Identity identity, String key, JSONObject req) {
        ledger.policy();
        return view(ledger.create(identity.tenant, identity.subject, key, "input", null, req));
    }

    /**
     * 把账本行投影为对外文件视图；到期但尚未删除的行在视图中标记为 expired。
     *
     * @param row 账本文件行
     * @return 视图 JSON：artifactId/inputId（按 kind）、fileName、mediaType、size、
     * sha256、createdAt、expiresAt、state、downloadable；绑定了 run 时附 runId
     */
    public JSONObject view(JSONObject row) {
        String kind = row.getString("kind");
        JSONObject out =
                new JSONObject()
                        .put(kind.equals("artifact") ? "artifactId" : "inputId", row.getString("file_id"))
                        .put("fileName", row.getString("file_name"))
                        .put("mediaType", row.getString("media_type"))
                        .put("size", row.getLong("size_bytes"))
                        .put("sha256", row.getString("sha256"))
                        .put("createdAt", row.getLong("created_at"))
                        .put("expiresAt", row.getLong("expires_at"));
        if (!row.isNull("run_id")) {
            out.put("runId", row.getString("run_id"));
        }
        String state = row.getString("status");
        if (!state.equals("deleted")
                && !state.equals("delete_pending")
                && row.getLong("expires_at") <= ledger.now()) {
            state = "expired";
        }
        return out.put("state", state)
                .put("downloadable", kind.equals("artifact") && state.equals("ready"));
    }

    /**
     * 以可信身份查询文件元数据；kind 不匹配按不存在处理。
     *
     * @param id   文件 ID
     * @param kind 期望类型（"input" / "artifact"）
     * @return 文件视图
     * @throws NullPointerException 适配器未绑定可信身份（TRUSTED_IDENTITY_REQUIRED）
     * @throws StoreException       文件不存在或类型不符（FILE_NOT_FOUND）
     */
    public JSONObject metadata(String id, String kind) {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        return metadata(identity, id, kind);
    }

    /**
     * 以指定身份查询文件元数据；kind 不匹配按不存在处理。
     *
     * @param identity 业务身份（tenant/subject 隔离）
     * @param id       文件 ID
     * @param kind     期望类型（"input" / "artifact"）
     * @return 文件视图
     * @throws StoreException 文件不存在或类型不符（FILE_NOT_FOUND）
     */
    public JSONObject metadata(ProfileRegistry.Identity identity, String id, String kind) {
        JSONObject row = ledger.get(identity.tenant, identity.subject, id);
        if (!row.getString("kind").equals(kind)) {
            throw new StoreException("FILE_NOT_FOUND");
        }
        return view(row);
    }

    /**
     * 上传文件内容（无中止条件）：valid 恒为 true。
     *
     * @param identity 业务身份
     * @param id       文件 ID
     * @param in       内容输入流
     * @return 上传完成后的文件视图
     * @throws Exception 见 {@link #upload(ProfileRegistry.Identity, String, InputStream, java.util.function.BooleanSupplier)}
     */
    private JSONObject upload(ProfileRegistry.Identity identity, String id, InputStream in) throws Exception {
        return upload(identity, id, in, () -> true);
    }

    /**
     * 在传输许可与根锁内执行一次内容上传：边写 .part 边算 SHA-256，逐块校验
     * 大小/期限与中止条件，写完后复核摘要与 root 配额，通过后原子落为 .data。
     *
     * <p>失败均以 StoreException 报告：许可不可用抛 "FILE_BUSY"；valid 变为 false
     * 抛 "FILE_TRANSFER_ABORTED"；超过声明大小、单文件上限或上传期限抛
     * "FILE_SIZE_OR_DEADLINE_EXCEEDED"；大小或摘要与声明不符抛
     * "FILE_DIGEST_MISMATCH"；root 用量超配额抛 "FILE_QUOTA_EXCEEDED"。上传流的
     * 最长读取期限为下载租约，到期由定时器强制关闭输入流。finally 中取消截止
     * 定时器、删除 .part 并释放 pin；断点重传时已存在的 .data 必须先校验一致性。
     *
     * @param identity 业务身份
     * @param id       文件 ID
     * @param in       内容输入流
     * @param valid    传输继续条件；每读完一块与写完后各检查一次
     * @return 上传完成后的文件视图
     * @throws Exception 上述 StoreException，以及锁/IO/账本失败
     */
    private JSONObject upload(ProfileRegistry.Identity identity, String id, InputStream in, java.util.function.BooleanSupplier valid) throws Exception {
        if (!transfers.tryAcquire()) {
            throw new StoreException("FILE_BUSY");
        }
        try {
            return disk.tryLocked(
                    () -> {
                        ledger.policy();
                        String pin = "upload-" + UUID.randomUUID();
                        JSONObject row = ledger.beginWrite(identity.tenant, identity.subject, id, pin);
                        Path part = path(row, ".part"), dest = path(row, ".data");
                        ScheduledFuture<?> deadline =
                                timer.schedule(
                                        () -> {
                                            try {
                                                in.close();
                                            } catch (IOException ignored) {
                                            }
                                        },
                                        config.download,
                                        TimeUnit.MILLISECONDS);
                        try {
                            MessageDigest digest = MessageDigest.getInstance("SHA-256");
                            long size = 0;
                            try (FileChannel out =
                                         FileChannel.open(
                                                 part,
                                                 StandardOpenOption.CREATE,
                                                 StandardOpenOption.TRUNCATE_EXISTING,
                                                 StandardOpenOption.WRITE)) {
                                byte[] buffer = new byte[8192];
                                int n;
                                while ((n = in.read(buffer)) != -1) {
                                    size += n;
                                    if (!valid.getAsBoolean()) {
                                        throw new StoreException("FILE_TRANSFER_ABORTED");
                                    }
                                    if (size > row.getLong("size_bytes")
                                            || size > config.maxFile
                                            || ledger.now() >= row.getLong("expires_at")) {
                                        throw new StoreException("FILE_SIZE_OR_DEADLINE_EXCEEDED");
                                    }
                                    digest.update(buffer, 0, n);
                                    ByteBuffer b = ByteBuffer.wrap(buffer, 0, n);
                                    while (b.hasRemaining()) {
                                        out.write(b);
                                    }
                                }
                                out.force(true);
                            }
                            if (!valid.getAsBoolean()) {
                                throw new StoreException("FILE_TRANSFER_ABORTED");
                            }
                            String actual = hex(digest.digest());
                            if (size != row.getLong("size_bytes") || !actual.equals(row.getString("sha256"))) {
                                throw new StoreException("FILE_DIGEST_MISMATCH");
                            }
                            if (disk.usage(disk.root)[0] > config.maxBytes) {
                                throw new StoreException("FILE_QUOTA_EXCEEDED");
                            }
                            if (Files.exists(dest)) {
                                if (!RuntimeSupport.sha(Files.readAllBytes(dest)).equals(actual)) {
                                    throw new StoreException("FILE_DIGEST_MISMATCH");
                                }
                                Files.delete(part);
                            } else {
                                Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE);
                            }
                            ledger.uploaded(identity.tenant, identity.subject, id, actual);
                            return view(ledger.get(identity.tenant, identity.subject, id));
                        } finally {
                            deadline.cancel(false);
                            try {
                                Files.deleteIfExists(part);
                            } finally {
                                ledger.unpin(pin);
                            }
                        }
                    });
        } finally {
            transfers.release();
        }
    }

    /**
     * 以可信身份上传 input 内容。
     *
     * @param id 文件 ID
     * @param in 内容输入流
     * @return 上传完成后的文件视图
     * @throws Exception 见 {@link #uploadInput(ProfileRegistry.Identity, String, InputStream)}
     */
    public JSONObject uploadInput(String id, InputStream in) throws Exception {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        return uploadInput(identity, id, in);
    }

    /**
     * 以指定身份上传 input 内容；目标不是 input 按不存在处理。
     *
     * @param identity 业务身份
     * @param id       文件 ID
     * @param in       内容输入流
     * @return 上传完成后的文件视图
     * @throws Exception 文件不存在或类型不符（FILE_NOT_FOUND），以及上传链路的其他失败
     */
    public JSONObject uploadInput(ProfileRegistry.Identity identity, String id, InputStream in) throws Exception {
        if (!ledger.get(identity.tenant, identity.subject, id).getString("kind").equals("input")) {
            throw new StoreException("FILE_NOT_FOUND");
        }
        return upload(identity, id, in);
    }

    /**
     * 以可信身份上传 input 内容（带中止条件）。
     *
     * @param id    文件 ID
     * @param in    内容输入流
     * @param valid 传输继续条件
     * @return 上传完成后的文件视图
     * @throws Exception 见 {@link #uploadInput(ProfileRegistry.Identity, String, InputStream, java.util.function.BooleanSupplier)}
     */
    public JSONObject uploadInput(String id, InputStream in, java.util.function.BooleanSupplier valid) throws Exception {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        return uploadInput(identity, id, in, valid);
    }

    /**
     * 以指定身份上传 input 内容（带中止条件）；目标不是 input 按不存在处理。
     *
     * @param identity 业务身份
     * @param id       文件 ID
     * @param in       内容输入流
     * @param valid    传输继续条件
     * @return 上传完成后的文件视图
     * @throws Exception 文件不存在或类型不符（FILE_NOT_FOUND），以及上传链路的其他失败
     */
    public JSONObject uploadInput(ProfileRegistry.Identity identity, String id, InputStream in, java.util.function.BooleanSupplier valid) throws Exception {
        if (!ledger.get(identity.tenant, identity.subject, id).getString("kind").equals("input")) {
            throw new StoreException("FILE_NOT_FOUND");
        }
        return upload(identity, id, in, valid);
    }

    /**
     * 以可信身份完成上传。
     *
     * @param id  文件 ID
     * @param sha 声明的内容摘要
     * @return ready 状态的文件视图
     * @throws Exception 见 {@link #complete(ProfileRegistry.Identity, String, String)}
     */
    public JSONObject complete(String id, String sha) throws Exception {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        return complete(identity, id, sha);
    }

    /**
     * 完成上传：核对磁盘内容与声明一致、类型受支持且文本/JSON 内容合法后，
     * 把文件固定为 ready 并落定 readyAt/expiresAt；重复 complete 不续期。
     *
     * @param identity 业务身份
     * @param id       文件 ID
     * @param sha      声明的内容摘要
     * @return ready 状态的文件视图
     * @throws Exception "FILE_EXPIRED"（已到期或状态不允许）、"FILE_UPLOAD_INCOMPLETE"
     *                   （内容缺失/大小/摘要不符）、"FILE_TYPE_UNSUPPORTED"（mediaType
     *                   不在白名单）、"FILE_TYPE_MISMATCH"（文本含 NUL），以及 JSON 内容
     *                   不合法时的 INVALID_JSON/SCHEMA_INVALID
     */
    public JSONObject complete(ProfileRegistry.Identity identity, String id, String sha) throws Exception {
        return disk.tryLocked(
                () -> {
                    JSONObject row = ledger.get(identity.tenant, identity.subject, id);
                    if (row.getLong("expires_at") <= ledger.now()
                            || !Arrays.asList("ready", "uploading").contains(row.getString("status"))) {
                        throw new StoreException("FILE_EXPIRED");
                    }
                    Path content = path(row, ".data");
                    if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(content) != row.getLong("size_bytes")
                            || !RuntimeSupport.sha(Files.readAllBytes(content)).equals(sha)) {
                        throw new StoreException("FILE_UPLOAD_INCOMPLETE");
                    }
                    String type = row.getString("media_type");
                    if (!Arrays.asList(
                                    "text/plain", "text/csv", "application/json", "application/octet-stream")
                            .contains(type)) {
                        throw new StoreException("FILE_TYPE_UNSUPPORTED");
                    }
                    if (!type.equals("application/octet-stream")) {
                        byte[] bytes = Files.readAllBytes(content);
                        String text = decode(bytes);
                        if (text.indexOf('\0') >= 0) {
                            throw new StoreException("FILE_TYPE_MISMATCH");
                        }
                        if (type.equals("application/json")) {
                            cube.service.harness.protocol.StrictJson.object(bytes);
                        }
                    }
                    return view(ledger.complete(identity.tenant, identity.subject, id, sha));
                });
    }

    /**
     * 严格 UTF-8 解码；畸形或不可映射字符直接报错而不是替换。
     *
     * @param bytes 待解码字节
     * @return 解码后的文本
     * @throws Exception 字节不是合法 UTF-8
     */
    private static String decode(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    /**
     * 字节数组转小写十六进制串（每字节两位，%02x）。
     *
     * @param b 字节数组
     * @return 小写十六进制字符串
     */
    private static String hex(byte[] b) {
        StringBuilder out = new StringBuilder();
        for (byte v : b) {
            out.append(String.format("%02x", v & 255));
        }
        return out.toString();
    }

    /**
     * 从文件视图中提取可被引用的稳定子集，用于回执与参数固定。
     *
     * @param view 文件视图
     * @return 仅含 artifactId、runId、fileName、mediaType、size、sha256、expiresAt 的对象
     */
    public JSONObject reference(JSONObject view) {
        JSONObject out = new JSONObject();
        for (String key :
                Arrays.asList(
                        "artifactId", "runId", "fileName", "mediaType", "size", "sha256", "expiresAt")) {
            out.put(key, view.get(key));
        }
        return out;
    }

    /**
     * 以可信身份列出某个 run 的全部 artifact。
     *
     * @param run run ID
     * @return artifact 视图数组（按 created_at,file_id 排序）
     * @throws NullPointerException 适配器未绑定可信身份（TRUSTED_IDENTITY_REQUIRED）
     */
    public JSONArray artifacts(String run) {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        return artifacts(identity, run);
    }

    /**
     * 以指定身份列出某个 run 的全部 artifact。
     *
     * @param identity 业务身份
     * @param run      run ID
     * @return artifact 视图数组（按 created_at,file_id 排序）
     */
    public JSONArray artifacts(ProfileRegistry.Identity identity, String run) {
        JSONArray rows = ledger.list(identity.tenant, identity.subject, run), out = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            out.put(view(rows.getJSONObject(i)));
        }
        return out;
    }

    /**
     * 以可信身份做交付前校验。
     *
     * @param run run ID
     * @throws Exception 见 {@link #checkDelivery(ProfileRegistry.Identity, String)}
     */
    public void checkDelivery(String run) throws Exception {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        checkDelivery(identity, run);
    }

    /**
     * 交付前校验：账本确认所有 artifact ready 且未到期，并逐个核对磁盘内容的
     * 存在性、大小与 SHA-256。
     *
     * @param identity 业务身份
     * @param run      run ID
     * @throws Exception 内容缺失/不符（FILE_DELIVERY_INVALID）或账本就绪校验失败
     */
    public void checkDelivery(ProfileRegistry.Identity identity, String run) throws Exception {
        disk.tryLocked(
                () -> {
                    ledger.checkDelivery(identity.tenant, identity.subject, run);
                    JSONArray rows = ledger.list(identity.tenant, identity.subject, run);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        Path content = path(row, ".data");
                        if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS)
                                || Files.size(content) != row.getLong("size_bytes")
                                || !RuntimeSupport.sha(Files.readAllBytes(content)).equals(row.getString("sha256"))) {
                            throw new StoreException("FILE_DELIVERY_INVALID");
                        }
                    }
                    return null;
                });
    }

    /**
     * 已验证的读取句柄：pin 在 close 之前一直持有，慢速消费者也不会被清理；
     * 下载租约到期由定时器强制 close。
     */
    public final class Download implements AutoCloseable {

        /**
         * 打开下载时的文件视图（state/downloadable 等）。
         */
        public final JSONObject metadata;

        /**
         * 下载响应头：Content-Type、Content-Length、Content-Disposition。
         */
        public final JSONObject headers;

        /**
         * 已验证摘要后打开的内容流。
         */
        public final InputStream input;

        /**
         * 持有中的下载 pin 标识；close 时释放。
         */
        private final String pin;

        /**
         * close 幂等标记；保证只释放一次。
         */
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 租约到期强制 close 的定时任务；尚未安排时为 null。
         */
        private volatile ScheduledFuture<?> deadline;

        /**
         * 私有构造：仅由 openDownload 在摘要校验通过后创建。
         *
         * @param row   账本文件行
         * @param input 已打开的内容流
         * @param pin   下载 pin 标识
         * @throws Exception 视图构造或文件名编码失败
         */
        private Download(JSONObject row, InputStream input, String pin) throws Exception {
            this.metadata = view(row);
            this.input = input;
            this.pin = pin;
            this.headers = new JSONObject().put("Content-Type", row.getString("media_type"))
                    .put("Content-Length", Long.toString(row.getLong("size_bytes")))
                    .put("Content-Disposition", "attachment; filename*=UTF-8''" +
                            java.net.URLEncoder.encode(row.getString("file_name"), "UTF-8").replace("+", "%20"));
        }

        /**
         * 安排租约到期任务：config.download 后强制 close（含慢速消费者）。
         */
        private void expire() {
            deadline = timer.schedule(() -> {
                try {
                    close();
                } catch (Exception ignored) {
                }
            }, config.download, TimeUnit.MILLISECONDS);
        }

        /**
         * 关闭句柄：幂等；取消到期任务、关闭输入流、释放 pin 与传输许可。
         *
         * @throws IOException 关闭输入流失败
         */
        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
            try {
                input.close();
            } finally {
                try {
                    ledger.unpin(pin);
                } finally {
                    transfers.release();
                }
            }
        }
    }

    /**
     * 以可信身份打开下载。
     *
     * @param id    文件 ID
     * @param query 原始 query（形如 handle=&lt;token&gt;）；为空表示进程内可信调用
     * @return 下载句柄
     * @throws Exception 见 {@link #openDownload(ProfileRegistry.Identity, String, String)}
     */
    public Download openDownload(String id, String query) throws Exception {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        return openDownload(identity, id, query);
    }

    /**
     * 打开下载：带 query 时先校验句柄格式/签名/归属，再获取传输许可与根锁，
     * 核对磁盘摘要后返回已验证的读取句柄。
     *
     * <p>句柄只允许 {@code handle=<base64url>.<hex>} 形式；非 artifact 目标按
     * 不存在处理。成功后传输许可由句柄持有直至 close，失败路径在原地释放。
     *
     * @param identity 业务身份
     * @param id       文件 ID
     * @param query    原始 query；为空表示进程内可信调用，不校验句柄
     * @return 下载句柄
     * @throws Exception "INVALID_DOWNLOAD_HANDLE"（句柄格式/签名/归属/过期不符）、
     *                   "FILE_BUSY"（许可或根锁不可用）、"FILE_NOT_FOUND"、
     *                   "FILE_DIGEST_MISMATCH" 等
     */
    public Download openDownload(ProfileRegistry.Identity identity, String id, String query) throws Exception {
        if (query != null && !query.isEmpty()) {
            if (!query.matches("handle=[A-Za-z0-9_-]+\\.[a-f0-9]{64}")) {
                throw new StoreException("INVALID_DOWNLOAD_HANDLE");
            }
            verifyHandle(identity, id, query.substring(7));
        }
        if (!transfers.tryAcquire()) {
            throw new StoreException("FILE_BUSY");
        }
        try {
            return disk.tryLocked(() -> {
                ledger.policy();
                String pin = "download-" + UUID.randomUUID();
                JSONObject row = ledger.pin(identity.tenant, identity.subject, id, pin, null, "download");
                InputStream input = null;
                try {
                    if (!row.getString("kind").equals("artifact")) {
                        throw new StoreException("FILE_NOT_FOUND");
                    }
                    Path content = path(row, ".data");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream check = Files.newInputStream(content)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = check.read(buffer)) != -1) {
                            digest.update(buffer, 0, n);
                        }
                    }
                    if (!hex(digest.digest()).equals(row.getString("sha256"))) {
                        throw new StoreException("FILE_DIGEST_MISMATCH");
                    }
                    input = Files.newInputStream(content);
                    Download download = new Download(row, input, pin);
                    download.expire();
                    return download;
                } catch (Exception e) {
                    if (input != null) {
                        try {
                            input.close();
                        } catch (IOException ignored) {
                        }
                    }
                    ledger.unpin(pin);
                    throw e;
                }
            });
        } catch (Exception e) {
            transfers.release();
            throw e;
        }
    }

    /**
     * 进程内兼容入口：把 openDownload 的结果写入 HttpExchange；无业务 HTTP
     * 监听器调用本适配器，业务下载走 dispatcher/SHM。
     *
     * @param id 文件 ID
     * @param x  HTTP 交换（句柄取自原始 query）
     * @throws Exception 打开下载或写出失败
     */
    public void download(String id, HttpExchange x) throws Exception {
        try (Download download = openDownload(id, x.getRequestURI().getRawQuery())) {
            for (String key : download.headers.keySet()) {
                x.getResponseHeaders().set(key, download.headers.getString(key));
            }
            x.getResponseHeaders().set("Cache-Control", "no-store");
            long size = download.metadata.getLong("size");
            x.sendResponseHeaders(200, size == 0 ? -1 : size);
            try (OutputStream out = x.getResponseBody()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = download.input.read(b)) != -1) {
                    out.write(b, 0, n);
                }
            }
        } finally {
            x.close();
        }
    }

    /**
     * 用 files.download.key 对文本计算 HmacSHA256 十六进制签名。
     *
     * @param text 待签名文本（句柄的 base64url 载荷）
     * @return 小写十六进制签名
     * @throws Exception 密钥或算法不可用
     */
    private String sign(String text) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        handleKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 以可信身份签发短期下载句柄。
     *
     * @param id 文件 ID
     * @return 句柄对象（url/expiresAt/requiresAuthentication）
     * @throws Exception 见 {@link #handle(ProfileRegistry.Identity, String)}
     */
    public JSONObject handle(String id) throws Exception {
        Objects.requireNonNull(identity, "TRUSTED_IDENTITY_REQUIRED");
        return handle(identity, id);
    }

    /**
     * 签发短期下载句柄：URL 安全 Base64(JSON 载荷) + "." + HmacSHA256 签名；
     * 有效期取文件到期与当前时间加下载租约的较小值，即不超过文件有效期。
     *
     * @param identity 业务身份
     * @param id       文件 ID
     * @return 句柄对象：url（相对地址）、expiresAt、requiresAuthentication=true
     * @throws Exception 签名失败，或目标不是 artifact/未就绪（FILE_NOT_FOUND 等）
     */
    public JSONObject handle(ProfileRegistry.Identity identity, String id) throws Exception {
        String pin = "handle-" + UUID.randomUUID();
        JSONObject r = ledger.pin(identity.tenant, identity.subject, id, pin, null, "download");
        try {
            if (!r.getString("kind").equals("artifact")) {
                throw new StoreException("FILE_NOT_FOUND");
            }
            long until = Math.min(r.getLong("expires_at"), ledger.now() + config.download);
            String text =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    RuntimeSupport.bytes(
                                            new JSONObject()
                                                    .put("file", id)
                                                    .put("tenant", identity.tenant)
                                                    .put("subject", identity.subject)
                                                    .put("expiresAt", until)));
            String token = text + "." + sign(text);
            return new JSONObject()
                    .put("url", "/harness/v1/artifacts/" + id + "/content?handle=" + token)
                    .put("expiresAt", until)
                    .put("requiresAuthentication", true);
        } finally {
            ledger.unpin(pin);
        }
    }

    /**
     * 校验下载句柄：拆分包体与签名并常数时间比较，再严格解析载荷 JSON，
     * 确认 file/tenant/subject 归属与未过期。
     *
     * @param identity 业务身份
     * @param id       文件 ID
     * @param value    句柄 token（&lt;base64url&gt;.&lt;hex&gt;）
     * @throws Exception 任一项不符均抛 "INVALID_DOWNLOAD_HANDLE"
     */
    private void verifyHandle(ProfileRegistry.Identity identity, String id, String value) throws Exception {
        String[] pieces = value.split("\\.", -1);
        if (pieces.length != 2
                || !MessageDigest.isEqual(
                sign(pieces[0]).getBytes(StandardCharsets.US_ASCII),
                pieces[1].getBytes(StandardCharsets.US_ASCII))) {
            throw new StoreException("INVALID_DOWNLOAD_HANDLE");
        }
        JSONObject r =
                cube.service.harness.protocol.StrictJson.object(Base64.getUrlDecoder().decode(pieces[0]));
        if (!id.equals(r.getString("file"))
                || !identity.tenant.equals(r.getString("tenant"))
                || !identity.subject.equals(r.getString("subject"))
                || r.getLong("expiresAt") <= ledger.now()) {
            throw new StoreException("INVALID_DOWNLOAD_HANDLE");
        }
    }

    /**
     * 由执行租约派生可信身份（无权限集合）；run 内工具调用以此隔离 tenant/subject。
     *
     * @param lease 执行租约
     * @return 仅含 tenant/subject 的身份
     */
    private ProfileRegistry.Identity owner(JdbcRunStore.Lease lease) {
        return new ProfileRegistry.Identity(lease.tenant, lease.subject, Collections.emptySet());
    }

    /**
     * artifact.write_text 工具：把 UTF-8 文本作为正式 artifact 落库并完成上传。
     *
     * @param lease 执行租约
     * @param call  工具调用标识（兼作幂等键）
     * @param args  工具参数（fileName/text）
     * @return ready 状态的文件视图
     * @throws Exception "FILE_EXPIRED"（最终未就绪），以及创建/上传/完成链路的其他失败
     */
    public JSONObject writeText(JdbcRunStore.Lease lease, String call, JSONObject args)
            throws Exception {
        ledger.requireTool(lease, "artifact.write_text");
        return writeTextArtifact(lease, call, args);
    }

    /** Result artifacts are authorized by the provider's durable job, without granting a standalone file tool. */
    public JSONObject writeProviderText(JdbcRunStore.Lease lease, String jobId, JSONObject args) throws Exception {
        return writeTextArtifact(lease, ledger.requireProvider(lease, jobId), args);
    }

    private JSONObject writeTextArtifact(JdbcRunStore.Lease lease, String call, JSONObject args) throws Exception {
        ProfileRegistry.Identity identity = owner(lease);
        byte[] content = args.getString("text").getBytes(StandardCharsets.UTF_8);
        JSONObject req =
                new JSONObject()
                        .put("fileName", args.getString("fileName"))
                        .put("mediaType", "text/plain")
                        .put("size", content.length)
                        .put("sha256", RuntimeSupport.sha(content));
        JSONObject row = ledger.create(lease, call, req);
        if (row.getString("status").equals("uploading")) {
            upload(identity, row.getString("file_id"), new ByteArrayInputStream(content));
            complete(identity, row.getString("file_id"), req.getString("sha256"));
        }
        JSONObject current = ledger.get(identity.tenant, identity.subject, row.getString("file_id"));
        if (!view(current).getString("state").equals("ready")) {
            throw new StoreException("FILE_EXPIRED");
        }
        return view(current);
    }

    /**
     * input.read_text 工具：读取已绑定 run 的 UTF-8 text/plain 输入（≤32 KiB），
     * 核对摘要后返回文本；consume pin 保留到 call.confirmed，崩溃后仍保留恢复材料。
     *
     * @param lease 执行租约
     * @param call  工具调用标识（参与 pin 命名）
     * @param id    input 文件 ID
     * @return inputId、sha256 与解码后的 text
     * @throws Exception "TEXT_INPUT_REQUIRED"（类型/大小不符）、"FILE_DIGEST_MISMATCH" 等
     */
    public JSONObject readText(JdbcRunStore.Lease lease, String call, String id) throws Exception {
        ledger.requireTool(lease, "input.read_text");
        ProfileRegistry.Identity identity = owner(lease);
        return disk.tryLocked(
                () -> {
                    ledger.owner(lease);
                    String pin =
                            "read-" + JdbcRunStore.hash(new JSONArray().put(lease.runId).put(call).put(id));
                    JSONObject row =
                            ledger.pin(identity.tenant, identity.subject, id, pin, lease.runId, "consume");
                    // consume pin 一直保留到 call.confirmed 持久化；崩溃后仍保留恢复材料。
                    if (!row.getString("kind").equals("input")
                            || !row.getString("media_type").equals("text/plain")
                            || row.getLong("size_bytes") > 32768) {
                        throw new StoreException("TEXT_INPUT_REQUIRED");
                    }
                    byte[] bytes = Files.readAllBytes(path(row, ".data"));
                    if (!RuntimeSupport.sha(bytes).equals(row.getString("sha256"))) {
                        throw new StoreException("FILE_DIGEST_MISMATCH");
                    }
                    return new JSONObject()
                            .put("inputId", id)
                            .put("sha256", row.getString("sha256"))
                            .put("text", decode(bytes));
                });
    }

    /**
     * 只读取 run 绑定 input 的已验证字节：既不是宿主路径，也不是任意 blob 句柄。
     *
     * @param lease      执行租约
     * @param call       工具调用标识（参与 pin 命名）
     * @param id         input 文件 ID
     * @param maxBytes   允许的最大字节数（1..1048576）
     * @param mediaTypes 允许的媒体类型集合
     * @return 内容字节
     * @throws Exception "INPUT_LIMIT_INVALID"（maxBytes 越界）、"INPUT_TYPE_OR_SIZE_FORBIDDEN"
     *                   （类型/大小不符）、"FILE_DIGEST_MISMATCH" 等
     */
    public byte[] readBound(JdbcRunStore.Lease lease, String call, String id, int maxBytes, Set<String> mediaTypes) throws Exception {
        ProfileRegistry.Identity identity = owner(lease);
        if (maxBytes < 1 || maxBytes > 1048576) {
            throw new StoreException("INPUT_LIMIT_INVALID");
        }
        return disk.tryLocked(() -> {
            ledger.owner(lease);
            String pin = "read-" + JdbcRunStore.hash(new JSONArray().put(lease.runId).put(call).put(id));
            JSONObject row = ledger.pin(identity.tenant, identity.subject, id, pin, lease.runId, "consume");
            if (!row.getString("kind").equals("input") || !mediaTypes.contains(row.getString("media_type")) || row.getLong("size_bytes") > maxBytes) {
                throw new StoreException("INPUT_TYPE_OR_SIZE_FORBIDDEN");
            }
            byte[] content = Files.readAllBytes(path(row, ".data"));
            if (content.length > maxBytes || !RuntimeSupport.sha(content).equals(row.getString("sha256"))) {
                throw new StoreException("FILE_DIGEST_MISMATCH");
            }
            return content;
        });
    }

    /**
     * 释放 read pin：由调用方在 call.confirmed 之后触发。
     *
     * @param run  run ID
     * @param call 工具调用标识
     * @param id   input 文件 ID
     */
    public void consumed(String run, String call, String id) {
        ledger.unpin("read-" + JdbcRunStore.hash(new JSONArray().put(run).put(call).put(id)));
    }

    /**
     * 文件工具分发：仅支持 artifact.write_text 与 input.read_text。
     *
     * @param lease 执行租约
     * @param call  工具调用标识
     * @param name  工具名
     * @param args  工具参数
     * @return 工具结果 JSON
     * @throws Exception 未知工具名（FILE_TOOL_UNKNOWN），或工具执行失败
     */
    public JSONObject invoke(JdbcRunStore.Lease lease, String call, String name, JSONObject args)
            throws Exception {
        if (name.equals("artifact.write_text")) {
            return writeText(lease, call, args);
        }
        if (name.equals("input.read_text")) {
            return readText(lease, call, args.getString("inputId"));
        }
        throw new StoreException("FILE_TOOL_UNKNOWN");
    }

    /**
     * 按权限集合生成文件工具声明（名称/权限/描述/参数 schema）；
     * 未授权的工具不出现在结果中。
     *
     * @param permissions 已授权工具名集合
     * @return 工具声明数组
     */
    public static JSONArray tools(Set<String> permissions) {
        JSONArray out = new JSONArray();
        for (String name : Arrays.asList("input.read_text", "artifact.write_text")) {
            if (permissions.contains(name)) {
                JSONObject props = new JSONObject();
                JSONArray required = new JSONArray();
                if (name.equals("input.read_text")) {
                    props.put("inputId", new JSONObject().put("type", "string").put("maxLength", 128));
                    required.put("inputId");
                } else {
                    props.put(
                            "fileName",
                            new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256));
                    props.put("text", new JSONObject().put("type", "string").put("maxLength", 32768));
                    required.put("fileName").put("text");
                }
                out.put(
                        new JSONObject()
                                .put("name", name)
                                .put("permission", name)
                                .put(
                                        "description",
                                        name.equals("input.read_text")
                                                ? "Read a bound UTF-8 text input by inputId"
                                                : "Persist a UTF-8 text artifact; returns its verified identity and expiration")
                                .put(
                                        "parameters",
                                        new JSONObject()
                                                .put("type", "object")
                                                .put("properties", props)
                                                .put("required", required)
                                                .put("additionalProperties", false)));
            }
        }
        return out;
    }

    /**
     * 单次清理扫描：刷新策略租约、恢复 pin、取一批候选文件并逐个
     * claim → 验证 → 删除 .part/.data → 标记 deleted；失败按退避记录错误码。
     *
     * @throws Exception 扫描整体失败（由 start 记录错误码）
     */
    public void scan() throws Exception {
        ledger.policy();
        disk.tryLocked(
                () -> {
                    ledger.policy();
                    ledger.recoverPins();
                    JSONArray rows = ledger.candidates();
                    if (config.cleanup) {
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject claim = ledger.claim(rows.getJSONObject(i).getString("file_id"));
                            if (claim == null) {
                                continue;
                            }
                            try {
                                ledger.verifyClaim(claim);
                                Files.deleteIfExists(path(claim, ".part"));
                                Files.deleteIfExists(path(claim, ".data"));
                                ledger.deleted(claim);
                            } catch (Exception e) {
                                ledger.failed(
                                        claim,
                                        e instanceof StoreException ? ((StoreException) e).code : "FILE_DELETE_FAILED");
                            }
                        }
                    }
                    return null;
                });
    }

    /**
     * 文件指标：账本统计加 lastError 与 cleanupEnabled。
     *
     * @return 指标 JSON（states/pins/failures/lastError/cleanupEnabled）
     */
    public JSONObject metrics() {
        return ledger
                .metrics()
                .put("lastError", lastError == null ? JSONObject.NULL : lastError)
                .put("cleanupEnabled", config.cleanup);
    }

    /**
     * 停止定时器并关闭账本；等待定时器终止最多 3 秒。
     */
    @Override
    public void close() {
        timer.shutdownNow();
        try {
            timer.awaitTermination(3, TimeUnit.SECONDS);
            ledger.close();
        } catch (Exception ignored) {
        }
    }
}
