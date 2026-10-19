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
 * Bounded, connection-bound SHM transfers. File bytes never enter an ActionDialect.
 */
final class HarnessTransfers implements AutoCloseable {

    private final FileService files;

    private final ConcurrentHashMap<String, Transfer> transfers = new ConcurrentHashMap<>();

    private final Semaphore active = new Semaphore(2);

    private final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(2), r -> {
        Thread t = new Thread(r, "harness-file-stream");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "harness-transfer-expiry");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    HarnessTransfers(FileService files) {
        this.files = files;
        timer.scheduleWithFixedDelay(() -> {
            for (Transfer t : transfers.values())
                if (System.currentTimeMillis() >= t.expires || !t.valid.getAsBoolean()) {
                    transfers.remove(t.id, t);
                    t.finish(HarnessBusiness.error(410, "FILE_TRANSFER_EXPIRED"));
                }
        }, 1, 1, TimeUnit.SECONDS);
    }

    synchronized JSONObject prepare(ProfileRegistry.Identity identity, String file, long transport, String credential, boolean upload, String query, BooleanSupplier valid) throws Exception {
        if (closed || transfers.size() >= 64 || !active.tryAcquire()) throw new StoreException("FILE_BUSY");
        FileService.Download download = null;
        try {
            JSONObject metadata;
            if (upload) metadata = files.metadata(identity, file, "input");
            else {
                download = files.openDownload(identity, file, query);
                metadata = download.metadata;
            }
            if (!valid.getAsBoolean()) throw new StoreException("FILE_TRANSFER_ABORTED");
            Transfer t = new Transfer(identity, file, transport, credential, upload, metadata, download, valid);
            transfers.put(t.id, t);
            return new JSONObject().put("transferId", t.id).put("expiresAt", t.expires)
                    .put("metadata", metadata).put("headers", download == null ? new JSONObject() : download.headers);
        } catch (Exception e) {
            try {
                if (download != null) download.close();
            } finally {
                active.release();
            }
            throw e;
        }
    }

    private Transfer owned(String id, long transport, String credential) {
        Transfer t = transfers.get(id);
        if (t == null || t.transport != transport || !HarnessService.equal(t.credential, credential))
            throw new StoreException("TRANSFER_NOT_FOUND");
        if (System.currentTimeMillis() >= t.expires || !t.valid.getAsBoolean()) {
            transfers.remove(id, t);
            t.finish(HarnessBusiness.error(410, "FILE_TRANSFER_EXPIRED"));
            throw new StoreException("FILE_EXPIRED");
        }
        return t;
    }

    JSONObject result(String id, long transport, String credential) {
        Transfer t = owned(id, transport, credential);
        JSONObject result = t.result;
        return result == null ? new JSONObject().put("complete", false)
                : new JSONObject().put("complete", true).put("response", result);
    }

    void abort(String id, long transport, String credential) {
        Transfer t = owned(id, transport, credential);
        transfers.remove(id, t);
        t.finish(HarnessBusiness.error(410, "FILE_TRANSFER_ABORTED"));
    }

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

    void startDownload(String id, long transport, String credential, Function<String, OutputStream> opener) {
        Transfer t = owned(id, transport, credential);
        if (t.upload) throw new StoreException("TRANSFER_NOT_FOUND");
        if (!t.claim()) return; // a repeated start must not create another stream
        try {
            io.execute(() -> {
                try {
                    OutputStream output = opener.apply(t.id);
                    if (output == null) throw new IOException("Stream unavailable");
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
                        if (!t.valid()) throw new IOException("Transfer cancelled");
                        output.write(buffer, 0, n);
                        size += n;
                    }
                    if (size != t.metadata.getLong("size")) throw new IOException("Incomplete file");
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

    void disconnect(long transport) {
        for (Transfer t : transfers.values())
            if (t.transport == transport) {
                transfers.remove(t.id, t);
                t.finish(HarnessBusiness.error(503, "HARNESS_UNAVAILABLE"));
            }
    }

    @Override
    public synchronized void close() {
        closed = true;
        timer.shutdownNow();
        for (Transfer t : transfers.values()) t.finish(HarnessBusiness.error(503, "HARNESS_UNAVAILABLE"));
        transfers.clear();
        io.shutdownNow();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) try {
            resource.close();
        } catch (Exception ignored) {
        }
    }

    private final class Transfer {
        final String id = "harness-" + UUID.randomUUID(), file, credential;
        final long transport, expires;
        final boolean upload;
        final ProfileRegistry.Identity identity;
        final JSONObject metadata;
        final FileService.Download download;
        final BooleanSupplier valid;
        final AtomicBoolean claimed = new AtomicBoolean(), finished = new AtomicBoolean();
        volatile JSONObject result;
        AutoCloseable stream;

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

        boolean valid() {
            return !finished.get() && !closed && System.currentTimeMillis() < expires && valid.getAsBoolean();
        }

        boolean claim() {
            return valid() && claimed.compareAndSet(false, true);
        }

        synchronized void finish(JSONObject response) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                if (response.getInt("status") >= 400 && stream instanceof cell.core.talk.PrimitiveOutputStream)
                    try {
                        ((cell.core.talk.PrimitiveOutputStream) stream).interrupt();
                    } catch (Exception ignored) {
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
