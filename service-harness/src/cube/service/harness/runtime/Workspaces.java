

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
 * Workspace paths are derived from tenant/subject/run, never client absolute paths.
 */
public final class Workspaces {
    private final CatalogStore catalog;
    private final AssetFiles files;

    public Workspaces(CatalogStore catalog, AssetFiles files) {
        this.catalog = catalog;
        this.files = files;
    }

    public JSONObject acquire(JdbcRunStore.Lease lease, long duration) throws Exception {
        JSONObject row = catalog.workspace(lease, duration);
        files.locked(() -> {
            Path dir = RuntimeSupport.resolve(files.root, row.getString("workspace_id") + "/marker", true).getParent();
            return dir;
        });
        return new JSONObject().put("workspaceId", row.getString("workspace_id")).put("fence", lease.fence).put("expiresAt", row.getLong("expires_at"))
                .put("maxBytes", row.getLong("max_bytes")).put("maxFiles", row.getInt("max_files"));
    }

    public JSONObject write(JdbcRunStore.Lease lease, String relative, byte[] value) {
        RuntimeSupport.path(relative);
        if (Arrays.stream(relative.split("/")).anyMatch(p -> p.startsWith(".harness-")))
            throw RuntimeSupport.error("UNSAFE_ASSET_PATH");
        return catalog.workspaceAction(lease, row -> files.locked(() -> {
            Path dir = RuntimeSupport.resolve(files.root, row.getString("workspace_id") + "/marker", false).getParent();
            Path target = RuntimeSupport.resolve(dir, relative, true);
            long old = Files.exists(target) ? Files.size(target) : 0;
            long[] usage = files.usage(dir);
            if (usage[0] - old + value.length > row.getLong("max_bytes") || usage[1] + (Files.exists(target) ? 0 : 1) > row.getInt("max_files")
                    || files.usage(files.root)[0] + value.length > files.maxBytes)
                throw RuntimeSupport.error("WORKSPACE_QUOTA_EXCEEDED");
            Path temp = target.resolveSibling(".harness-" + UUID.randomUUID());
            try {
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer b = ByteBuffer.wrap(value);
                    while (b.hasRemaining()) channel.write(b);
                    channel.force(true);
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            return new JSONObject().put("path", relative).put("sha256", RuntimeSupport.sha(value)).put("size", value.length);
        }));
    }

    public byte[] read(JdbcRunStore.Lease lease, String relative) {
        return catalog.workspaceAction(lease, row -> files.locked(() -> {
            Path path = RuntimeSupport.resolve(files.root, row.getString("workspace_id") + "/" + RuntimeSupport.path(relative), false);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw RuntimeSupport.error("WORKSPACE_FILE_NOT_FOUND");
            try (InputStream in = Files.newInputStream(path)) {
                return RuntimeSupport.read(in, (int) Math.min(2097152, row.getLong("max_bytes")));
            }
        }));
    }

    public void release(JdbcRunStore.Lease lease) {
        catalog.releaseWorkspace(lease);
    }
}
