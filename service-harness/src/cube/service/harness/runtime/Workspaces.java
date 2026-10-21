

package cube.service.harness.runtime;

import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.UUID;

/**
 * 工作区路径由 tenant/subject/run 摘要派生，绝不使用客户端绝对路径。
 *
 * <p>{@link #acquire} 绑定数据库时钟、owner/fence 与 run 租约上限；目录 ID 由
 * 存储层按身份派生。每次读写都在租约事务内重验当前租约，写入同时执行 runtime
 * 的字节/文件数配额与资产根总配额。相对路径经真实路径解析，禁止访问工作区外；
 * {@code .harness-} 前缀路径保留给内部临时文件，客户端不可使用。
 * 只有 executionSettlement=confirmed 才能 release；未知执行不删除工作区。
 */
public final class Workspaces {
    /**
     * 目录存储：工作区行获取、租约动作与释放均经此访问。
     */
    private final CatalogStore catalog;

    /**
     * 资产文件根：工作区目录位于其下，读写走根级文件锁与配额。
     */
    private final AssetFiles files;

    /**
     * 构造工作区门面。
     *
     * @param catalog 目录存储
     * @param files   资产文件根
     */
    public Workspaces(CatalogStore catalog, AssetFiles files) {
        this.catalog = catalog;
        this.files = files;
    }

    /**
     * 获取（或按租约创建）工作区行，并在根锁内确保工作区目录存在。
     *
     * <p>目录路径为 {@code <workspace_id>/marker} 的父目录；marker 文件本身
     * 只是占位，真正配额以行上的 max_bytes/max_files 为准。
     *
     * @param lease    当前 run 租约；提供 owner/fence
     * @param duration 租约时长（数据库时钟单位，由存储层解释）
     * @return {@code {workspaceId, fence, expiresAt, maxBytes, maxFiles}}
     * @throws Exception 租约失效或目录创建失败
     */
    public JSONObject acquire(JdbcRunStore.Lease lease, long duration) throws Exception {
        JSONObject row = catalog.workspace(lease, duration);
        files.locked(() -> {
            Path dir = RuntimeSupport.resolve(files.root, row.getString("workspace_id") + "/marker", true).getParent();
            return dir;
        });
        return new JSONObject().put("workspaceId", row.getString("workspace_id")).put("fence", lease.fence).put("expiresAt", row.getLong("expires_at"))
                .put("maxBytes", row.getLong("max_bytes")).put("maxFiles", row.getInt("max_files"));
    }

    /**
     * 在租约事务内原子写入工作区文件（临时文件 + force + ATOMIC_MOVE）。
     *
     * <p>三重配额：替换后工作区字节数、工作区文件数、资产根已用字节 + 本次写入
     * 均不得超限（WORKSPACE_QUOTA_EXCEEDED）。替换场景先扣除旧文件大小再核算。
     *
     * @param lease    当前 run 租约
     * @param relative 工作区内相对路径；拒绝 {@code .harness-} 前缀段
     * @param value    文件内容
     * @return {@code {path, sha256, size}}
     * @throws cube.service.harness.store.StoreException 路径不安全（UNSAFE_ASSET_PATH）
     *                                                   或配额超限（WORKSPACE_QUOTA_EXCEEDED）
     */
    public JSONObject write(JdbcRunStore.Lease lease, String relative, byte[] value) {
        RuntimeSupport.path(relative);
        if (Arrays.stream(relative.split("/")).anyMatch(p -> p.startsWith(".harness-"))) {
            throw RuntimeSupport.error("UNSAFE_ASSET_PATH");
        }
        return catalog.workspaceAction(lease, row -> files.locked(() -> {
            Path dir = RuntimeSupport.resolve(files.root, row.getString("workspace_id") + "/marker", false).getParent();
            Path target = RuntimeSupport.resolve(dir, relative, true);
            long old = Files.exists(target) ? Files.size(target) : 0;
            long[] usage = files.usage(dir);
            if (usage[0] - old + value.length > row.getLong("max_bytes") || usage[1] + (Files.exists(target) ? 0 : 1) > row.getInt("max_files")
                    || files.usage(files.root)[0] + value.length > files.maxBytes) {
                throw RuntimeSupport.error("WORKSPACE_QUOTA_EXCEEDED");
            }
            Path temp = target.resolveSibling(".harness-" + UUID.randomUUID());
            try {
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer b = ByteBuffer.wrap(value);
                    while (b.hasRemaining()) {
                        channel.write(b);
                    }
                    channel.force(true);
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            return new JSONObject().put("path", relative).put("sha256", RuntimeSupport.sha(value)).put("size", value.length);
        }));
    }

    /**
     * 在租约事务内读取工作区文件。
     *
     * <p>目标必须是普通文件（NOFOLLOW_LINKS）；读取上限为 min(2 MiB, 行 max_bytes)，
     * 超限抛 ASSET_TOO_LARGE。
     *
     * @param lease    当前 run 租约
     * @param relative 工作区内相对路径
     * @return 文件内容字节
     * @throws cube.service.harness.store.StoreException 文件不存在或不是普通文件
     *                                                   （WORKSPACE_FILE_NOT_FOUND）
     */
    public byte[] read(JdbcRunStore.Lease lease, String relative) {
        return catalog.workspaceAction(lease, row -> files.locked(() -> {
            Path path = RuntimeSupport.resolve(files.root, row.getString("workspace_id") + "/" + RuntimeSupport.path(relative), false);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw RuntimeSupport.error("WORKSPACE_FILE_NOT_FOUND");
            }
            try (InputStream in = Files.newInputStream(path)) {
                return RuntimeSupport.read(in, (int) Math.min(2097152, row.getLong("max_bytes")));
            }
        }));
    }

    /**
     * 释放工作区（仅 executionSettlement=confirmed 时由存储层放行）。
     *
     * @param lease 当前 run 租约
     */
    public void release(JdbcRunStore.Lease lease) {
        catalog.releaseWorkspace(lease);
    }
}
