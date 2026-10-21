/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.core.talk.PrimitiveInputStream;
import cube.service.harness.files.FileService;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.store.StoreException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 有界、连接绑定的 SHM 文件传输协调：文件字节只走同连接的原生 SHM 流，不进入 ActionDialect。
 * <p>并发模型：{@link #transfers} 为 ConcurrentHashMap，复合操作借助方法级 synchronized
 * 与 remove(key, value) 保证原子性；{@link #active} 信号量把在途传输限制为 2 个，
 * prepare tryAcquire、finish/异常路径 release；{@link #closed} 为 volatile，close 后拒绝新建；
 * 字节读写在独立有界 io 线程池执行，不占用 worker 心跳池。
 * <p>不变量：transferId 同时绑定 transport、身份、文件与截止时间；每个 Transfer 的
 * finish 与 claim 用 AtomicBoolean CAS 保证幂等——重复启动不会创建第二个流，
 * 重复完成只认第一次结果。到期（含租约/会话失效）由单线程定时器每秒清理。
 */
final class HarnessTransfers implements AutoCloseable {

    /**
     * 文件服务：元数据查询、下载打开与上传落库。
     */
    private final FileService files;

    /**
     * transferId → Transfer；并发读写由 Map 自身保证，检查后移除用 remove(key, value) 防ABA。
     */
    private final ConcurrentHashMap<String, Transfer> transfers = new ConcurrentHashMap<>();

    /**
     * 在途传输信号量，许可数 2；prepare 获取，finish 与失败路径释放。
     */
    private final Semaphore active = new Semaphore(2);

    /**
     * 字节流读写线程池：2 核心 2 队列，AbortPolicy 拒绝时转 429/FILE_BUSY；守护线程。
     */
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(2), r -> {
        Thread t = new Thread(r, "harness-file-stream");
        t.setDaemon(true);
        return t;
    });
    /**
     * 传输到期定时器：单线程守护，每秒扫描过期或失效传输；close 时立即关闭。
     */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "harness-transfer-expiry");
        t.setDaemon(true);
        return t;
    });
    /**
     * 关闭标记：true 后拒绝新传输；volatile 保证跨线程可见。
     */
    private volatile boolean closed;

    /**
     * 绑定文件服务并启动每秒到期的定时清理任务。
     *
     * @param files 文件服务，供元数据查询、下载打开与上传落库
     */
    HarnessTransfers(FileService files) {
        this.files = files;
        timer.scheduleWithFixedDelay(() -> {
            for (Transfer t : transfers.values()) {
                if (System.currentTimeMillis() >= t.expires || !t.valid.getAsBoolean()) {
                    transfers.remove(t.id, t);
                    t.finish(HarnessBusiness.error(410, "FILE_TRANSFER_EXPIRED"));
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 准备一次传输：校验容量（关闭中/记录数 ≥64/无许可均拒 FILE_BUSY）、
     * 取元数据或打开下载，随后登记 Transfer 并返回 transferId 与到期时间。
     * 方法级 synchronized 与 close 互斥，避免关闭过程中新建传输。
     *
     * @param identity   调用方身份，用于元数据与下载的权限校验
     * @param file       文件 ID（input/artifact）
     * @param transport  Cell transport 会话标识，传输全程绑定该连接
     * @param credential 调用方 Bearer 原文，仅用于传输属主比对，不落日志
     * @param upload     true 为上传会话，false 为下载会话
     * @param query      下载句柄的原始 query，可为 null
     * @param valid      会话/令牌有效性探针，准备阶段复核一次
     * @return {@code {transferId, expiresAt, metadata, headers}}；上传时 headers 为空对象
     * @throws StoreException FILE_BUSY：已关闭、记录数 ≥64 或信号量无许可；
     *                        FILE_TRANSFER_ABORTED：准备阶段会话/令牌已失效
     * @throws Exception      元数据查询或下载打开失败（含权限类 StoreException）
     */
    synchronized JSONObject prepare(ProfileRegistry.Identity identity, String file, long transport, String credential, boolean upload, String query, BooleanSupplier valid) throws Exception {
        if (closed || transfers.size() >= 64 || !active.tryAcquire()) {
            throw new StoreException("FILE_BUSY");
        }
        FileService.Download download = null;
        try {
            JSONObject metadata;
            if (upload) {
                metadata = files.metadata(identity, file, "input");
            } else {
                download = files.openDownload(identity, file, query);
                metadata = download.metadata;
            }
            if (!valid.getAsBoolean()) {
                throw new StoreException("FILE_TRANSFER_ABORTED");
            }
            Transfer t = new Transfer(identity, file, transport, credential, upload, metadata, download, valid);
            transfers.put(t.id, t);
            return new JSONObject().put("transferId", t.id).put("expiresAt", t.expires)
                    .put("metadata", metadata).put("headers", download == null ? new JSONObject() : download.headers);
        } catch (Exception e) {
            try {
                if (download != null) {
                    download.close();
                }
            } finally {
                active.release();
            }
            throw e;
        }
    }

    /**
     * 按属主关系取传输：transferId 必须存在，且 transport 与 credential 完全匹配，
     * 否则按不存在处理；已到期或会话/令牌失效时先摘除并收尾，再按过期拒绝。
     *
     * @param id         transferId
     * @param transport  Cell transport 会话标识
     * @param credential 调用方 Bearer 原文
     * @return 归属当前连接与身份的 Transfer
     * @throws StoreException TRANSFER_NOT_FOUND：ID 不存在或跨连接/跨身份访问；
     *                        FILE_EXPIRED：已到期或会话/令牌已失效
     */
    private Transfer owned(String id, long transport, String credential) {
        Transfer t = transfers.get(id);
        if (t == null || t.transport != transport || !HarnessService.equal(t.credential, credential)) {
            throw new StoreException("TRANSFER_NOT_FOUND");
        }
        if (System.currentTimeMillis() >= t.expires || !t.valid.getAsBoolean()) {
            transfers.remove(id, t);
            t.finish(HarnessBusiness.error(410, "FILE_TRANSFER_EXPIRED"));
            throw new StoreException("FILE_EXPIRED");
        }
        return t;
    }

    /**
     * 查询传输结果：未完成返回 complete=false，已完成带回最终响应。
     *
     * @param id         transferId
     * @param transport  Cell transport 会话标识
     * @param credential 调用方 Bearer 原文
     * @return {@code {complete:false}} 或 {@code {complete:true, response}}
     * @throws StoreException TRANSFER_NOT_FOUND 或 FILE_EXPIRED（见 owned）
     */
    JSONObject result(String id, long transport, String credential) {
        Transfer t = owned(id, transport, credential);
        JSONObject result = t.result;
        return result == null ? new JSONObject().put("complete", false)
                : new JSONObject().put("complete", true).put("response", result);
    }

    /**
     * 中止传输：摘除记录并按 410/FILE_TRANSFER_ABORTED 收尾。
     *
     * @param id         transferId
     * @param transport  Cell transport 会话标识
     * @param credential 调用方 Bearer 原文
     * @throws StoreException TRANSFER_NOT_FOUND 或 FILE_EXPIRED（见 owned）
     */
    void abort(String id, long transport, String credential) {
        Transfer t = owned(id, transport, credential);
        transfers.remove(id, t);
        t.finish(HarnessBusiness.error(410, "FILE_TRANSFER_ABORTED"));
    }

    /**
     * 接收上传字节流：流名即 transferId，须存在、绑定同一 transport 且为上传会话，
     * 经 claim 抢占成功后才接管流；非归属流直接静默关闭。
     * 实际写库在 io 线程池执行，完成后按结果 finish。
     *
     * @param transport Cell transport 会话标识
     * @param input     客户端经 SHM 送达的字节流
     */
    void receive(long transport, PrimitiveInputStream input) {
        Transfer t = transfers.get(input.getName());
        if (t == null || t.transport != transport || !t.upload || !t.claim()) {
            closeQuietly(input);
            return;
        }
        synchronized (t) {
            if (t.finished.get()) {
                closeQuietly(input);
                return;
            }
            t.stream = input;
        }
        try {
            io.execute(() -> {
                try {
                    JSONObject result = files.uploadInput(t.identity, t.file, input, t::valid);
                    t.finish(HarnessBusiness.reply(200, result));
                } catch (Exception e) {
                    t.finish(HarnessBusiness.failure(e));
                }
            });
        } catch (RejectedExecutionException e) {
            t.finish(HarnessBusiness.error(429, "FILE_BUSY"));
        }
    }

    /**
     * 开始下载：属主校验通过且为下载会话后，经 claim 防重复启动
     * （重复 start 不创建第二个流），字节拷贝在 io 线程池执行，
     * 写满且与元数据 size 一致才按成功 finish。
     *
     * @param id         transferId
     * @param transport  Cell transport 会话标识
     * @param credential 调用方 Bearer 原文
     * @param opener     SHM 流打开器，按 transferId 取得输出流；返回 null 按打开失败处理
     * @throws StoreException TRANSFER_NOT_FOUND：ID 不存在/跨连接访问，或对上传会话发起下载；
     *                        FILE_EXPIRED：已到期或会话/令牌已失效
     */
    void startDownload(String id, long transport, String credential, Function<String, OutputStream> opener) {
        Transfer t = owned(id, transport, credential);
        if (t.upload) {
            throw new StoreException("TRANSFER_NOT_FOUND");
        }
        if (!t.claim()) {
            return; // 重复的 start 不得再创建另一个流
        }
        try {
            io.execute(() -> {
                try {
                    OutputStream output = opener.apply(t.id);
                    if (output == null) {
                        throw new IOException("Stream unavailable");
                    }
                    synchronized (t) {
                        if (t.finished.get()) {
                            closeQuietly(output);
                            return;
                        }
                        t.stream = output;
                    }
                    byte[] buffer = new byte[8192];
                    int n;
                    long size = 0;
                    while ((n = t.download.input.read(buffer)) != -1) {
                        if (!t.valid()) {
                            throw new IOException("Transfer cancelled");
                        }
                        output.write(buffer, 0, n);
                        size += n;
                    }
                    if (size != t.metadata.getLong("size")) {
                        throw new IOException("Incomplete file");
                    }
                    output.flush();
                    t.finish(HarnessBusiness.reply(200, t.metadata));
                } catch (Exception e) {
                    t.finish(HarnessBusiness.error(503, "FILE_DOWNLOAD_INTERRUPTED"));
                }
            });
        } catch (RejectedExecutionException e) {
            t.finish(HarnessBusiness.error(429, "FILE_BUSY"));
        }
    }

    /**
     * transport 断开回调：摘除该连接上的全部传输并按 503/HARNESS_UNAVAILABLE 收尾，
     * 不影响其他连接的传输。
     *
     * @param transport 断开的 Cell transport 会话标识
     */
    void disconnect(long transport) {
        for (Transfer t : transfers.values()) {
            if (t.transport == transport) {
                transfers.remove(t.id, t);
                t.finish(HarnessBusiness.error(503, "HARNESS_UNAVAILABLE"));
            }
        }
    }

    /**
     * 关闭传输层：置关闭标记、停定时器、按 503/HARNESS_UNAVAILABLE 收尾全部在途传输、
     * 清空记录表，最后停 io 池并等待最多 3 秒；被中断时恢复中断标记。
     */
    @Override
    public synchronized void close() {
        closed = true;
        timer.shutdownNow();
        for (Transfer t : transfers.values()) {
            t.finish(HarnessBusiness.error(503, "HARNESS_UNAVAILABLE"));
        }
        transfers.clear();
        io.shutdownNow();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 静默关闭资源，忽略关闭过程中的异常。
     *
     * @param resource 待关闭资源；为 null 时直接返回
     */
    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 单次传输记录：身份、文件、连接绑定、元数据、到期时间与最终结果。
     * claimed/finished 用 AtomicBoolean CAS 保证幂等；result 为 volatile 供跨线程发布；
     * stream 的写入与 finish 的关闭同在 synchronized(t) 保护下进行。
     */
    private final class Transfer {
        /**
         * transferId，随机生成，全局唯一。
         */
        final String id = "harness-" + UUID.randomUUID();
        /**
         * 文件 ID（input/artifact）。
         */
        final String file;
        /**
         * 发起传输的调用方 Bearer 原文，仅用于属主比对。
         */
        final String credential;
        /**
         * 绑定的 Cell transport 会话标识。
         */
        final long transport;
        /**
         * 到期时间（epoch 毫秒）：元数据 expiresAt 与 now+下载租期 取较小值。
         */
        final long expires;
        /**
         * true 为上传会话，false 为下载会话。
         */
        final boolean upload;
        /**
         * 调用方身份，用于上传落库与下载权限。
         */
        final ProfileRegistry.Identity identity;
        /**
         * 文件元数据 JSON（含 size、expiresAt 等）。
         */
        final JSONObject metadata;
        /**
         * 下载句柄；上传会话为 null，finish 时关闭。
         */
        final FileService.Download download;
        /**
         * 会话/令牌有效性探针，来自业务层。
         */
        final BooleanSupplier valid;
        /**
         * 流抢占标记：CAS 保证重复 start/receive 只有一个流。
         */
        final AtomicBoolean claimed = new AtomicBoolean();
        /**
         * 完成标记：CAS 保证 finish 只第一次生效。
         */
        final AtomicBoolean finished = new AtomicBoolean();
        /**
         * 最终响应 JSON（成功回执或错误响应）；volatile 供 io 线程与业务线程之间发布。
         */
        volatile JSONObject result;
        /**
         * 在途字节流（上传的输入流或下载的输出流）；synchronized(t) 保护下赋值。
         */
        AutoCloseable stream;

        /**
         * 构造传输记录并计算到期时间：元数据 expiresAt 与 now + 下载租期 取较小值。
         *
         * @param identity   调用方身份
         * @param file       文件 ID（input/artifact）
         * @param transport  绑定的 Cell transport 会话标识
         * @param credential 调用方 Bearer 原文
         * @param upload     true 为上传会话，false 为下载会话
         * @param metadata   文件元数据 JSON
         * @param download   下载句柄；上传会话传 null
         * @param valid      会话/令牌有效性探针
         */
        Transfer(ProfileRegistry.Identity identity, String file, long transport, String credential, boolean upload, JSONObject metadata,
                 FileService.Download download, BooleanSupplier valid) {
            this.identity = identity;
            this.file = file;
            this.transport = transport;
            this.credential = credential;
            this.upload = upload;
            this.metadata = metadata;
            this.download = download;
            this.valid = valid;
            this.expires = Math.min(metadata.getLong("expiresAt"), System.currentTimeMillis() + files.config.download);
        }

        /**
         * @return 传输当前是否有效：未完成、未关闭、未到期且会话/令牌有效
         */
        boolean valid() {
            return !finished.get() && !closed && System.currentTimeMillis() < expires && valid.getAsBoolean();
        }

        /**
         * 抢占传输：仅在有效时把 claimed 由 false CAS 为 true。
         *
         * @return 抢占成功返回 true；已抢占、已失效返回 false
         */
        boolean claim() {
            return valid() && claimed.compareAndSet(false, true);
        }

        /**
         * 收尾传输（幂等，仅首次调用生效）：错误响应且流为 SHM 输出流时先中断，
         * 再关闭流与下载句柄，最后发布 result 并释放信号量许可。
         *
         * @param response 最终响应：成功回执或错误响应信封
         */
        synchronized void finish(JSONObject response) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                if (response.getInt("status") >= 400 && stream instanceof cell.core.talk.PrimitiveOutputStream) {
                    try {
                        ((cell.core.talk.PrimitiveOutputStream) stream).interrupt();
                    } catch (Exception ignored) {
                    }
                }
                closeQuietly(stream);
            } finally {
                try {
                    closeQuietly(download);
                } finally {
                    result = response;
                    active.release();
                }
            }
        }
    }
}
