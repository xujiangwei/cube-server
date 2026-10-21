/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 资产文件根：专用私有目录、跨进程串行化、配额与不可变 blob 存储。
 *
 * <p>每个根目录先经 {@link RuntimeSupport#safeRoot} 收敛为绝对真实路径，再由两级锁独占：
 * JVM 内按根索引的 {@link ReentrantLock} 加 OS 文件锁（{@code .harness-lock}）。
 * 首次初始化写入 {@code .harness-assets} 身份标记；之后打开同一目录必须 namespace 一致，
 * 且拒绝无标记的非空目录（{@code ASSET_ROOT_NOT_EMPTY}）。
 *
 * <p>写入采用「临时文件 + force + 原子移动」保证内容寻址的 blob 不可变；
 * 失败遗留的 blob/临时文件仍计入配额，工程上保守保留、不做自动 GC。
 * 所有路径访问拒绝符号链接与越界真实路径，防止经链接逃逸根目录。
 */
public final class AssetFiles {
    /**
     * 根路径到 JVM 内可重入锁的全局索引；键为规范化后的真实根路径，并发安全。
     */
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /**
     * 资产根的绝对真实路径；构造时校验后固定，运行期不可变。
     */
    public final Path root;

    /**
     * 整个资产根允许占用的最大字节数（含 blob 与失败遗留的临时文件）。
     */
    public final long maxBytes;

    /**
     * 持锁作用域内执行的动作抽象；允许抛出受检异常并由调用方原样传播。
     *
     * @param <T> 动作返回值类型
     */
    public interface Action<T> {
        /**
         * 执行动作。
         *
         * @return 动作结果，可为 {@code null}
         * @throws Exception 动作执行期间的任何异常
         */
        T run() throws Exception;
    }

    /**
     * 打开（必要时初始化）一个资产根，固定其身份标记与配额。
     *
     * <p>初始化在持锁作用域内完成：标记文件是符号链接直接拒绝；标记已存在时
     * 内容必须等于 {@code namespace}，否则视为根被挪用；标记不存在时要求目录
     * 为空（仅允许 {@code .harness-lock} 存在），随后以 CREATE_NEW 原子写入身份标记。
     *
     * @param path      资产根目录；必须是已存在的绝对目录
     * @param maxBytes  根配额字节数；必须大于等于 1
     * @param namespace 根身份字符串；同时用于 id 合法性校验与标记内容比对
     * @throws Exception 配额非法（INVALID_ASSET_QUOTA）、根不安全（UNSAFE_ASSET_ROOT）、
     *                   身份不符（ASSET_ROOT_IDENTITY_MISMATCH）或目录非空（ASSET_ROOT_NOT_EMPTY）
     */
    public AssetFiles(Path path, long maxBytes, String namespace) throws Exception {
        RuntimeSupport.id(namespace);
        root = RuntimeSupport.safeRoot(path);
        if (maxBytes < 1) {
            throw RuntimeSupport.error("INVALID_ASSET_QUOTA");
        }
        this.maxBytes = maxBytes;
        locked(() -> {
            Path marker = root.resolve(".harness-assets");
            if (Files.isSymbolicLink(marker)) {
                throw RuntimeSupport.error("UNSAFE_ASSET_ROOT");
            }
            if (Files.exists(marker)) {
                if (!new String(Files.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8).equals(namespace)) {
                    throw RuntimeSupport.error("ASSET_ROOT_IDENTITY_MISMATCH");
                }
            } else {
                try (Stream<Path> entries = Files.list(root)) {
                    if (entries.anyMatch(p -> !p.getFileName().toString().equals(".harness-lock"))) {
                        throw RuntimeSupport.error("ASSET_ROOT_NOT_EMPTY");
                    }
                }
                Files.write(marker, namespace.getBytes(java.nio.charset.StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            }
            return null;
        });
    }

    /**
     * 在根的 JVM 锁 + OS 文件锁保护下执行动作（阻塞式获取两级锁）。
     *
     * @param action 持锁执行的动作
     * @param <T>    动作返回值类型
     * @return 动作返回值
     * @throws Exception 动作异常，或路径不安全（UNSAFE_ASSET_PATH）
     */
    public <T> T locked(Action<T> action) throws Exception {
        return locked(action, false);
    }

    /**
     * 有界准入的持锁执行：任一级锁获取失败立即报错，绝不让 API/coordinator 线程阻塞在文件流后面。
     *
     * @param action 持锁执行的动作
     * @param <T>    动作返回值类型
     * @return 动作返回值
     * @throws Exception 动作异常，或锁被占用（FILE_BUSY）、路径不安全（UNSAFE_ASSET_PATH）
     */
    public <T> T tryLocked(Action<T> action) throws Exception {
        return locked(action, true);
    }

    /**
     * 持锁执行的核心实现：先取 JVM 内按根索引的可重入锁，再取 OS 文件锁。
     *
     * <p>{@code immediate=true} 时两级锁均采用 tryLock 语义，任一级失败立即抛 FILE_BUSY；
     * 阻塞模式下 OS 锁用 {@link FileChannel#lock()} 等待。finally 只释放 JVM 锁，
     * OS 锁由 try-with-resources 保证关闭通道时释放。
     *
     * @param action    持锁执行的动作
     * @param immediate true 表示有界准入（不等待），false 表示阻塞等待
     * @param <T>       动作返回值类型
     * @return 动作返回值
     * @throws Exception 动作异常，或 FILE_BUSY / UNSAFE_ASSET_PATH
     */
    private <T> T locked(Action<T> action, boolean immediate) throws Exception {
        ReentrantLock local = LOCKS.computeIfAbsent(root, p -> new ReentrantLock());
        if (immediate) {
            if (!local.tryLock()) {
                throw RuntimeSupport.error("FILE_BUSY");
            }
        } else {
            local.lock();
        }
        try {
            Path lock = root.resolve(".harness-lock");
            if (Files.isSymbolicLink(lock)) {
                throw RuntimeSupport.error("UNSAFE_ASSET_PATH");
            }
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = immediate ? channel.tryLock() : channel.lock()) {
                if (ignored == null) {
                    throw RuntimeSupport.error("FILE_BUSY");
                }
                return action.run();
            }
        } finally {
            local.unlock();
        }
    }

    /**
     * 统计目录树的字节数与文件数，用于配额核算。
     *
     * <p>遍历中逐项执行符号链接与真实路径越界检查；内部管理文件
     * （{@code .harness-lock}、{@code .harness-assets}）不计入统计。
     * 字节累加使用 {@link Math#addExact}，long 溢出立即失败而不是静默回绕。
     *
     * @param directory 待统计目录；必须位于根内
     * @return 长度为 2 的数组：{@code [字节数, 文件数]}
     * @throws IOException 目录遍历失败或路径不安全（UNSAFE_ASSET_PATH）
     */
    public long[] usage(Path directory) throws IOException {
        long bytes = 0, files = 0;
        try (Stream<Path> paths = Files.walk(directory)) {
            Iterator<Path> it = paths.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (Files.isSymbolicLink(p) || !p.toRealPath().startsWith(root)) {
                    throw RuntimeSupport.error("UNSAFE_ASSET_PATH");
                }
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                        && !Arrays.asList(".harness-lock", ".harness-assets").contains(p.getFileName().toString())) {
                    bytes = Math.addExact(bytes, Files.size(p));
                    files++;
                }
            }
        }
        return new long[]{bytes, files};
    }

    /**
     * 把内容寻址 digest 解析为根内 {@code blobs/} 下的规范路径。
     *
     * @param digest 64 位小写十六进制 SHA256 字符串
     * @return blob 文件路径；不保证文件已存在
     * @throws IOException digest 非法（INVALID_DIGEST）或路径不安全（UNSAFE_ASSET_PATH）
     */
    public Path blob(String digest) throws IOException {
        return RuntimeSupport.resolve(root, "blobs/" + RuntimeSupport.digest(digest), true);
    }

    /**
     * 批量写入不可变 blob：先全量校验摘要与配额，再逐文件原子落盘。
     *
     * <p>两阶段设计：第一阶段校验每个键值对的内容摘要与键一致、已存在 blob 的
     * 落盘内容摘要也与键一致，并核算新增字节不超根配额；第二阶段才写临时文件、
     * {@code force} 后以 ATOMIC_MOVE 移到目标路径。任一步失败遗留的临时文件
     * 仍计入配额；DB 提交失败只留下已验证、已计额的孤儿 blob。
     *
     * @param contents digest(hex) 到内容字节的映射；键必须是值的小写十六进制 SHA256
     * @throws Exception 摘要不符（ASSET_DIGEST_MISMATCH）、配额超限（REPOSITORY_QUOTA_EXCEEDED）等
     */
    public void put(Map<String, byte[]> contents) throws Exception {
        locked(() -> {
            long added = 0;
            for (Map.Entry<String, byte[]> e : contents.entrySet()) {
                if (!RuntimeSupport.sha(e.getValue()).equals(e.getKey())) {
                    throw RuntimeSupport.error("ASSET_DIGEST_MISMATCH");
                }
                Path dest = blob(e.getKey());
                if (!Files.exists(dest)) {
                    added += e.getValue().length;
                } else if (!RuntimeSupport.sha(Files.readAllBytes(dest)).equals(e.getKey())) {
                    throw RuntimeSupport.error("ASSET_DIGEST_MISMATCH");
                }
            }
            if (usage(root)[0] + added > maxBytes) {
                throw RuntimeSupport.error("REPOSITORY_QUOTA_EXCEEDED");
            }
            for (Map.Entry<String, byte[]> e : contents.entrySet()) {
                Path dest = blob(e.getKey());
                if (Files.exists(dest)) {
                    continue;
                }
                Path temp = dest.resolveSibling(".harness-" + UUID.randomUUID());
                try {
                    try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(e.getValue());
                        while (b.hasRemaining()) {
                            ch.write(b);
                        }
                        ch.force(true);
                    }
                    Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            return null;
        });
    }

    /**
     * 读取 blob 内容，返回前复核摘要，保证内容寻址存储未被篡改。
     *
     * @param digest 64 位小写十六进制 SHA256 字符串
     * @param max    允许读取的最大字节数；超出抛 ASSET_TOO_LARGE
     * @return blob 的全部字节（长度不超过 {@code max}）
     * @throws Exception blob 不存在（ASSET_UNAVAILABLE）、摘要不符（ASSET_DIGEST_MISMATCH）、
     *                   digest 非法（INVALID_DIGEST）或读取超限（ASSET_TOO_LARGE）
     */
    public byte[] read(String digest, int max) throws Exception {
        Path p = blob(digest);
        if (!Files.exists(p)) {
            throw RuntimeSupport.error("ASSET_UNAVAILABLE");
        }
        try (InputStream in = Files.newInputStream(p)) {
            byte[] value = RuntimeSupport.read(in, max);
            if (!RuntimeSupport.sha(value).equals(digest)) {
                throw RuntimeSupport.error("ASSET_DIGEST_MISMATCH");
            }
            return value;
        }
    }
}
