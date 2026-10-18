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
 * Dedicated private roots, cross-process serialization, quotas and immutable blobs.
 */
public final class AssetFiles {
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    public final Path root;
    public final long maxBytes;

    public interface Action<T> {
        T run() throws Exception;
    }

    public AssetFiles(Path path, long maxBytes, String namespace) throws Exception {
        RuntimeSupport.id(namespace);
        root = RuntimeSupport.safeRoot(path);
        if (maxBytes < 1) throw RuntimeSupport.error("INVALID_ASSET_QUOTA");
        this.maxBytes = maxBytes;
        locked(() -> {
            Path marker = root.resolve(".harness-assets");
            if (Files.isSymbolicLink(marker)) throw RuntimeSupport.error("UNSAFE_ASSET_ROOT");
            if (Files.exists(marker)) {
                if (!new String(Files.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8).equals(namespace))
                    throw RuntimeSupport.error("ASSET_ROOT_IDENTITY_MISMATCH");
            } else {
                try (Stream<Path> entries = Files.list(root)) {
                    if (entries.anyMatch(p -> !p.getFileName().toString().equals(".harness-lock")))
                        throw RuntimeSupport.error("ASSET_ROOT_NOT_EMPTY");
                }
                Files.write(marker, namespace.getBytes(java.nio.charset.StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            }
            return null;
        });
    }

    public <T> T locked(Action<T> action) throws Exception {
        return locked(action, false);
    }

    /** Bounded admission for live file operations; never park API/coordinator threads behind a stream. */
    public <T> T tryLocked(Action<T> action) throws Exception {
        return locked(action, true);
    }

    private <T> T locked(Action<T> action, boolean immediate) throws Exception {
        ReentrantLock local = LOCKS.computeIfAbsent(root, p -> new ReentrantLock());
        if (immediate) {
            if (!local.tryLock()) throw RuntimeSupport.error("FILE_BUSY");
        } else local.lock();
        try {
            Path lock = root.resolve(".harness-lock");
            if (Files.isSymbolicLink(lock)) throw RuntimeSupport.error("UNSAFE_ASSET_PATH");
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = immediate ? channel.tryLock() : channel.lock()) {
                if (ignored == null) throw RuntimeSupport.error("FILE_BUSY");
                return action.run();
            }
        } finally {
            local.unlock();
        }
    }

    public long[] usage(Path directory) throws IOException {
        long bytes = 0, files = 0;
        try (Stream<Path> paths = Files.walk(directory)) {
            Iterator<Path> it = paths.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (Files.isSymbolicLink(p) || !p.toRealPath().startsWith(root))
                    throw RuntimeSupport.error("UNSAFE_ASSET_PATH");
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && !Arrays.asList(".harness-lock", ".harness-assets").contains(p.getFileName().toString())) {
                    bytes = Math.addExact(bytes, Files.size(p));
                    files++;
                }
            }
        }
        return new long[]{bytes, files};
    }

    public Path blob(String digest) throws IOException {
        return RuntimeSupport.resolve(root, "blobs/" + RuntimeSupport.digest(digest), true);
    }

    public void put(Map<String, byte[]> contents) throws Exception {
        locked(() -> {
            long added = 0;
            for (Map.Entry<String, byte[]> e : contents.entrySet()) {
                if (!RuntimeSupport.sha(e.getValue()).equals(e.getKey()))
                    throw RuntimeSupport.error("ASSET_DIGEST_MISMATCH");
                Path dest = blob(e.getKey());
                if (!Files.exists(dest)) added += e.getValue().length;
                else if (!RuntimeSupport.sha(Files.readAllBytes(dest)).equals(e.getKey()))
                    throw RuntimeSupport.error("ASSET_DIGEST_MISMATCH");
            }
            if (usage(root)[0] + added > maxBytes) throw RuntimeSupport.error("REPOSITORY_QUOTA_EXCEEDED");
            for (Map.Entry<String, byte[]> e : contents.entrySet()) {
                Path dest = blob(e.getKey());
                if (Files.exists(dest)) continue;
                Path temp = dest.resolveSibling(".harness-" + UUID.randomUUID());
                try {
                    try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(e.getValue());
                        while (b.hasRemaining()) ch.write(b);
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

    public byte[] read(String digest, int max) throws Exception {
        Path p = blob(digest);
        if (!Files.exists(p)) throw RuntimeSupport.error("ASSET_UNAVAILABLE");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] value = RuntimeSupport.read(in, max);
            if (!RuntimeSupport.sha(value).equals(digest)) throw RuntimeSupport.error("ASSET_DIGEST_MISMATCH");
            return value;
        }
    }
}
