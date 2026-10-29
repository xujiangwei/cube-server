/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.usage;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 终态证据的落盘与补记器。
 *
 * <p>模型调用的终态证据先以 fsync 写入本地日志（一个 meter + revision 一个文件），再尝试写数据库。
 * 数据库不可用时文件保留，由 {@link #replay()} 每 10 秒幂等重放一次，重启后同样先重放、成功再恢复派发。
 * 因此“已经发生过的消费”不会因为数据库抖动而丢失。</p>
 *
 * <p>{@code healthy} 标志反映存储可用性：一旦写库失败就置为 {@code false} ，此时新的模型派发会被
 * {@link #begin} 拒绝，避免在无法记账的情况下继续消耗额度。补记成功后自动恢复。</p>
 *
 * <p>日志目录必须位于持久磁盘，并与数据库一同备份。</p>
 */
public final class UsageWriter implements AutoCloseable {

    /**
     * 底层账本，负责真正的库内写入。
     */
    public final UsageLedger ledger;

    /**
     * 终态证据日志目录。
     */
    private final Path directory;

    /**
     * 补记线程，每 10 秒触发一次重放。
     */
    private final ScheduledExecutorService replay;

    /**
     * 存储是否可用。写库失败置为 {@code false} ，补记成功后恢复。
     */
    private volatile boolean healthy = true;

    /**
     * 创建写入器，建立日志目录并立即补记一次历史证据。
     *
     * @param ledger    底层账本。
     * @param directory 终态证据日志目录，不存在时自动创建。
     * @throws IllegalStateException 目录无法创建。
     */
    public UsageWriter(UsageLedger ledger, Path directory) {
        this.ledger = ledger;
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("AI_USAGE_JOURNAL_UNAVAILABLE", e);
        }
        replay = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ai-usage-replay");
            t.setDaemon(true);
            return t;
        });
        // 启动先补记：重启前遗留的证据必须先落库，再对外提供能力。
        replay();
        replay.scheduleWithFixedDelay(this::replay, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * 登记一次开始的模型调用。
     *
     * <p>委托身份不在此处记账；存储不可用时直接拒绝，调用方不得继续派发。</p>
     *
     * @param context 结算身份。
     * @param id      meter ID。
     * @param model   模型名。
     * @param unit    执行单元 ID。
     * @param now     派发开始时间，UTC epoch 毫秒。
     * @throws IllegalStateException 存储暂时不可用。
     */
    public void begin(UsageContext context, String id, String model, String unit, long now) {
        if (context.delegated) {
            return;
        }
        if (!healthy) {
            throw new IllegalStateException("AI_USAGE_TEMPORARILY_UNAVAILABLE");
        }
        try {
            ledger.begin(context, id, model, unit, now);
        } catch (RuntimeException e) {
            healthy = false;
            throw e;
        }
    }

    /**
     * 自主流的准入检查。
     *
     * <p>只探测存储是否可用，不登记具体调用；单次尝试仍需调用 {@link #begin} 。</p>
     *
     * @throws IllegalStateException 存储不可用，或元数据读取失败。
     */
    public void checkAvailable() {
        if (!healthy) {
            throw new IllegalStateException("AI_USAGE_TEMPORARILY_UNAVAILABLE");
        }
        try (java.sql.Connection c = ledger.connections.open()) {
            ledger.metadata(c);
        } catch (Exception e) {
            healthy = false;
            throw new IllegalStateException("AI_USAGE_STORAGE_UNAVAILABLE", e);
        }
    }

    /**
     * 落盘并提交一次调用的终态证据。
     *
     * <p>先写日志再改库：库写失败时证据仍在磁盘上，留给 {@link #replay()} 重放。同一 meter 与 revision
     * 的重复提交必须内容一致，否则视为冲突，避免修订被静默覆盖。</p>
     *
     * @param evidence 终态证据，须含 {@code meterId} 、{@code revision} 等字段。
     * @throws IllegalStateException 日志不可写，或同一修订的证据内容冲突。
     */
    public synchronized void finish(JSONObject evidence) {
        Path target = directory.resolve(UsageLedger.hash(evidence.getString("meterId") + ":" + evidence.getLong("revision")) + ".json");
        try {
            byte[] bytes = evidence.toString().getBytes(StandardCharsets.UTF_8);
            if (Files.exists(target)) {
                JSONObject old = new JSONObject(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
                if (!UsageLedger.evidenceHash(old).equals(UsageLedger.evidenceHash(evidence))) {
                    throw new IOException("USAGE_JOURNAL_CONFLICT");
                }
            } else {
                // 先写临时文件并 fsync，再原子改名，避免留下半截 JSON。
                Path temp = Files.createTempFile(directory, "evidence-", ".tmp");
                try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target);
                }
            }
            try {
                ledger.settle(evidence);
                Files.deleteIfExists(target);
            } catch (RuntimeException db) {
                // 留待重放：此处故意不抛出，终态证据已经持久化。
                healthy = false;
            }
        } catch (IOException e) {
            healthy = false;
            throw new IllegalStateException("AI_USAGE_JOURNAL_UNAVAILABLE", e);
        }
    }

    /**
     * 重放日志目录里的全部终态证据。
     *
     * <p>逐条提交并删除已落库的文件；最后重新读取一次元数据确认存储恢复。任一条失败都会中止本轮，
     * 未处理的文件留到下一次重试。</p>
     */
    public synchronized void replay() {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : entries) {
                ledger.settle(new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
                Files.delete(file);
            }
            try (java.sql.Connection c = ledger.connections.open()) {
                ledger.metadata(c);
            }
            healthy = true;
        } catch (Exception e) {
            healthy = false;
        }
    }

    /**
     * 存储当前是否可用。
     *
     * @return 可用返回 {@code true} 。
     */
    public boolean healthy() {
        return healthy;
    }

    /**
     * 停止补记线程并做最后一次重放。
     */
    public void close() {
        replay.shutdownNow();
        replay();
    }
}
