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
 * Dedicated local file adapter. Cross-JVM root lock protects actual handles as well as paths.
 */
public final class FileService implements AutoCloseable {
    public final FileStore ledger;
    public final FileConfig config;
    private final AssetFiles disk;
    private final ProfileRegistry.Identity identity;
    private final ScheduledExecutorService timer =
            Executors.newScheduledThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "harness-files");
                        t.setDaemon(true);
                        return t;
                    });
    private final Semaphore transfers = new Semaphore(2);
    private volatile String lastError;
    private final String handleKey;

    public FileService(JdbcRunStore store, Properties p, ProfileRegistry.Identity identity)
            throws Exception {
        this.identity = identity;
        config = new FileConfig(p);
        handleKey = p.getProperty("files.download.key", "");
        if (handleKey.length() < 32) throw new IllegalArgumentException("FILE_HANDLE_KEY_REQUIRED");
        Path root = Paths.get(Objects.requireNonNull(p.getProperty("files.root"), "files.root"));
        Path repository = Paths.get(p.getProperty("execution.repository")).toAbsolutePath().normalize();
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.startsWith(repository) || repository.startsWith(normalized))
            throw new IllegalArgumentException("FILE_ROOT_MUST_BE_INDEPENDENT");
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
                    Path marker = disk.root.resolve(".harness-file-identity");
                    if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                                || Files.isSymbolicLink(marker)
                                || !marker.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(marker.toRealPath())
                                || Files.size(marker) > 128)
                            throw new StoreException("FILE_ROOT_IDENTITY_MISMATCH");
                    } else
                        Files.write(
                                marker,
                                UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII),
                                StandardOpenOption.CREATE_NEW);
                    ledger.bindRoot(new String(Files.readAllBytes(marker), StandardCharsets.US_ASCII));
                    ledger.policy();
                    ledger.recoverPins();
                    return null;
                });
    }

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

    private Path path(JSONObject row, String suffix) throws Exception {
        if (!config.rootId.equals(row.getString("root_id")))
            throw new StoreException("FILE_ROOT_IDENTITY_MISMATCH");
        return RuntimeSupport.resolve(
                disk.root, "objects/" + RuntimeSupport.digest(row.getString("storage_key")) + suffix, true);
    }

    public JSONObject create(String key, JSONObject req) {
        ledger.policy();
        return view(ledger.create(identity.tenant, identity.subject, key, "input", null, req));
    }

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
        if (!row.isNull("run_id")) out.put("runId", row.getString("run_id"));
        String state = row.getString("status");
        if (!state.equals("deleted")
                && !state.equals("delete_pending")
                && row.getLong("expires_at") <= ledger.now()) state = "expired";
        return out.put("state", state)
                .put("downloadable", kind.equals("artifact") && state.equals("ready"));
    }

    public JSONObject metadata(String id, String kind) {
        JSONObject row = ledger.get(identity.tenant, identity.subject, id);
        if (!row.getString("kind").equals(kind)) throw new StoreException("FILE_NOT_FOUND");
        return view(row);
    }

    private JSONObject upload(String id, InputStream in) throws Exception {
        return upload(id, in, () -> true);
    }

    private JSONObject upload(String id, InputStream in, java.util.function.BooleanSupplier valid) throws Exception {
        if (!transfers.tryAcquire()) throw new StoreException("FILE_BUSY");
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
                                    if (!valid.getAsBoolean()) throw new StoreException("FILE_TRANSFER_ABORTED");
                                    if (size > row.getLong("size_bytes")
                                            || size > config.maxFile
                                            || ledger.now() >= row.getLong("expires_at"))
                                        throw new StoreException("FILE_SIZE_OR_DEADLINE_EXCEEDED");
                                    digest.update(buffer, 0, n);
                                    ByteBuffer b = ByteBuffer.wrap(buffer, 0, n);
                                    while (b.hasRemaining()) out.write(b);
                                }
                                out.force(true);
                            }
                            if (!valid.getAsBoolean()) throw new StoreException("FILE_TRANSFER_ABORTED");
                            String actual = hex(digest.digest());
                            if (size != row.getLong("size_bytes") || !actual.equals(row.getString("sha256")))
                                throw new StoreException("FILE_DIGEST_MISMATCH");
                            if (disk.usage(disk.root)[0] > config.maxBytes)
                                throw new StoreException("FILE_QUOTA_EXCEEDED");
                            if (Files.exists(dest)) {
                                if (!RuntimeSupport.sha(Files.readAllBytes(dest)).equals(actual))
                                    throw new StoreException("FILE_DIGEST_MISMATCH");
                                Files.delete(part);
                            } else Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE);
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

    public JSONObject uploadInput(String id, InputStream in) throws Exception {
        if (!ledger.get(identity.tenant, identity.subject, id).getString("kind").equals("input"))
            throw new StoreException("FILE_NOT_FOUND");
        return upload(id, in);
    }

    public JSONObject uploadInput(String id, InputStream in, java.util.function.BooleanSupplier valid) throws Exception {
        if (!ledger.get(identity.tenant, identity.subject, id).getString("kind").equals("input"))
            throw new StoreException("FILE_NOT_FOUND");
        return upload(id, in, valid);
    }

    public JSONObject complete(String id, String sha) throws Exception {
        return disk.tryLocked(
                () -> {
                    JSONObject row = ledger.get(identity.tenant, identity.subject, id);
                    if (row.getLong("expires_at") <= ledger.now()
                            || !Arrays.asList("ready", "uploading").contains(row.getString("status")))
                        throw new StoreException("FILE_EXPIRED");
                    Path content = path(row, ".data");
                    if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(content) != row.getLong("size_bytes")
                            || !RuntimeSupport.sha(Files.readAllBytes(content)).equals(sha))
                        throw new StoreException("FILE_UPLOAD_INCOMPLETE");
                    String type = row.getString("media_type");
                    if (!Arrays.asList(
                                    "text/plain", "text/csv", "application/json", "application/octet-stream")
                            .contains(type)) throw new StoreException("FILE_TYPE_UNSUPPORTED");
                    if (!type.equals("application/octet-stream")) {
                        byte[] bytes = Files.readAllBytes(content);
                        String text = decode(bytes);
                        if (text.indexOf('\0') >= 0) throw new StoreException("FILE_TYPE_MISMATCH");
                        if (type.equals("application/json"))
                            cube.service.harness.protocol.StrictJson.object(bytes);
                    }
                    return view(ledger.complete(identity.tenant, identity.subject, id, sha));
                });
    }

    private static String decode(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static String hex(byte[] b) {
        StringBuilder out = new StringBuilder();
        for (byte v : b) out.append(String.format("%02x", v & 255));
        return out.toString();
    }

    public JSONObject reference(JSONObject view) {
        JSONObject out = new JSONObject();
        for (String key :
                Arrays.asList(
                        "artifactId", "runId", "fileName", "mediaType", "size", "sha256", "expiresAt"))
            out.put(key, view.get(key));
        return out;
    }

    public JSONArray artifacts(String run) {
        JSONArray rows = ledger.list(identity.tenant, identity.subject, run), out = new JSONArray();
        for (int i = 0; i < rows.length(); i++) out.put(view(rows.getJSONObject(i)));
        return out;
    }

    public void checkDelivery(String run) throws Exception {
        disk.tryLocked(
                () -> {
                    ledger.checkDelivery(identity.tenant, identity.subject, run);
                    JSONArray rows = ledger.list(identity.tenant, identity.subject, run);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        Path content = path(row, ".data");
                        if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS)
                                || Files.size(content) != row.getLong("size_bytes")
                                || !RuntimeSupport.sha(Files.readAllBytes(content)).equals(row.getString("sha256")))
                            throw new StoreException("FILE_DELIVERY_INVALID");
                    }
                    return null;
                });
    }

    /** A verified read handle; its pin remains held until close, including slow consumers. */
    public final class Download implements AutoCloseable {
        public final JSONObject metadata, headers;
        public final InputStream input;
        private final String pin;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> deadline;

        private Download(JSONObject row, InputStream input, String pin) throws Exception {
            this.metadata = view(row);
            this.input = input;
            this.pin = pin;
            this.headers = new JSONObject().put("Content-Type", row.getString("media_type"))
                    .put("Content-Length", Long.toString(row.getLong("size_bytes")))
                    .put("Content-Disposition", "attachment; filename*=UTF-8''" +
                            java.net.URLEncoder.encode(row.getString("file_name"), "UTF-8").replace("+", "%20"));
        }

        private void expire() {
            deadline = timer.schedule(() -> { try { close(); } catch (Exception ignored) { } }, config.download, TimeUnit.MILLISECONDS);
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            if (deadline != null) deadline.cancel(false);
            try { input.close(); }
            finally { try { ledger.unpin(pin); } finally { transfers.release(); } }
        }
    }

    public Download openDownload(String id, String query) throws Exception {
        if (query != null && !query.isEmpty()) {
            if (!query.matches("handle=[A-Za-z0-9_-]+\\.[a-f0-9]{64}")) throw new StoreException("INVALID_DOWNLOAD_HANDLE");
            verifyHandle(id, query.substring(7));
        }
        if (!transfers.tryAcquire()) throw new StoreException("FILE_BUSY");
        try {
            return disk.tryLocked(() -> {
                ledger.policy();
                String pin = "download-" + UUID.randomUUID();
                JSONObject row = ledger.pin(identity.tenant, identity.subject, id, pin, null, "download");
                InputStream input = null;
                try {
                    if (!row.getString("kind").equals("artifact")) throw new StoreException("FILE_NOT_FOUND");
                    Path content = path(row, ".data");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream check = Files.newInputStream(content)) {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = check.read(buffer)) != -1) digest.update(buffer, 0, n);
                    }
                    if (!hex(digest.digest()).equals(row.getString("sha256"))) throw new StoreException("FILE_DIGEST_MISMATCH");
                    input = Files.newInputStream(content);
                    Download download = new Download(row, input, pin);
                    download.expire();
                    return download;
                } catch (Exception e) {
                    if (input != null) try { input.close(); } catch (IOException ignored) { }
                    ledger.unpin(pin);
                    throw e;
                }
            });
        } catch (Exception e) { transfers.release(); throw e; }
    }

    /** Compatibility for in-process callers; no business HTTP listener invokes this adapter. */
    public void download(String id, HttpExchange x) throws Exception {
        try (Download download = openDownload(id, x.getRequestURI().getRawQuery())) {
            for (String key : download.headers.keySet()) x.getResponseHeaders().set(key, download.headers.getString(key));
            x.getResponseHeaders().set("Cache-Control", "no-store");
            long size = download.metadata.getLong("size");
            x.sendResponseHeaders(200, size == 0 ? -1 : size);
            try (OutputStream out = x.getResponseBody()) {
                byte[] b = new byte[8192]; int n;
                while ((n = download.input.read(b)) != -1) out.write(b, 0, n);
            }
        } finally { x.close(); }
    }

    private String sign(String text) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        handleKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    public JSONObject handle(String id) throws Exception {
        String pin = "handle-" + UUID.randomUUID();
        JSONObject r = ledger.pin(identity.tenant, identity.subject, id, pin, null, "download");
        try {
            if (!r.getString("kind").equals("artifact")) throw new StoreException("FILE_NOT_FOUND");
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

    private void verifyHandle(String id, String value) throws Exception {
        String[] pieces = value.split("\\.", -1);
        if (pieces.length != 2
                || !MessageDigest.isEqual(
                sign(pieces[0]).getBytes(StandardCharsets.US_ASCII),
                pieces[1].getBytes(StandardCharsets.US_ASCII)))
            throw new StoreException("INVALID_DOWNLOAD_HANDLE");
        JSONObject r =
                cube.service.harness.protocol.StrictJson.object(Base64.getUrlDecoder().decode(pieces[0]));
        if (!id.equals(r.getString("file"))
                || !identity.tenant.equals(r.getString("tenant"))
                || !identity.subject.equals(r.getString("subject"))
                || r.getLong("expiresAt") <= ledger.now())
            throw new StoreException("INVALID_DOWNLOAD_HANDLE");
    }

    public JSONObject writeText(JdbcRunStore.Lease lease, String call, JSONObject args)
            throws Exception {
        byte[] content = args.getString("text").getBytes(StandardCharsets.UTF_8);
        JSONObject req =
                new JSONObject()
                        .put("fileName", args.getString("fileName"))
                        .put("mediaType", "text/plain")
                        .put("size", content.length)
                        .put("sha256", RuntimeSupport.sha(content));
        JSONObject row = ledger.create(lease, call, req);
        if (row.getString("status").equals("uploading")) {
            upload(row.getString("file_id"), new ByteArrayInputStream(content));
            complete(row.getString("file_id"), req.getString("sha256"));
        }
        JSONObject current = ledger.get(identity.tenant, identity.subject, row.getString("file_id"));
        if (!view(current).getString("state").equals("ready")) throw new StoreException("FILE_EXPIRED");
        return view(current);
    }

    public JSONObject readText(JdbcRunStore.Lease lease, String call, String id) throws Exception {
        return disk.tryLocked(
                () -> {
                    ledger.owner(lease);
                    String pin =
                            "read-" + JdbcRunStore.hash(new JSONArray().put(lease.runId).put(call).put(id));
                    JSONObject row =
                            ledger.pin(identity.tenant, identity.subject, id, pin, lease.runId, "consume");
                    // Keep the consume pin through durable call.confirmed; a crash retains the recovery
                    // material.
                    if (!row.getString("kind").equals("input")
                            || !row.getString("media_type").equals("text/plain")
                            || row.getLong("size_bytes") > 32768) throw new StoreException("TEXT_INPUT_REQUIRED");
                    byte[] bytes = Files.readAllBytes(path(row, ".data"));
                    if (!RuntimeSupport.sha(bytes).equals(row.getString("sha256")))
                        throw new StoreException("FILE_DIGEST_MISMATCH");
                    return new JSONObject()
                            .put("inputId", id)
                            .put("sha256", row.getString("sha256"))
                            .put("text", decode(bytes));
                });
    }

    /**
     * Verified bytes of a run-bound input only; neither a host path nor an arbitrary blob handle.
     */
    public byte[] readBound(JdbcRunStore.Lease lease, String call, String id, int maxBytes, Set<String> mediaTypes) throws Exception {
        if (maxBytes < 1 || maxBytes > 1048576) throw new StoreException("INPUT_LIMIT_INVALID");
        return disk.tryLocked(() -> {
            ledger.owner(lease);
            String pin = "read-" + JdbcRunStore.hash(new JSONArray().put(lease.runId).put(call).put(id));
            JSONObject row = ledger.pin(identity.tenant, identity.subject, id, pin, lease.runId, "consume");
            if (!row.getString("kind").equals("input") || !mediaTypes.contains(row.getString("media_type")) || row.getLong("size_bytes") > maxBytes)
                throw new StoreException("INPUT_TYPE_OR_SIZE_FORBIDDEN");
            byte[] content = Files.readAllBytes(path(row, ".data"));
            if (content.length > maxBytes || !RuntimeSupport.sha(content).equals(row.getString("sha256")))
                throw new StoreException("FILE_DIGEST_MISMATCH");
            return content;
        });
    }

    public void consumed(String run, String call, String id) {
        ledger.unpin("read-" + JdbcRunStore.hash(new JSONArray().put(run).put(call).put(id)));
    }

    public JSONObject invoke(JdbcRunStore.Lease lease, String call, String name, JSONObject args)
            throws Exception {
        if (name.equals("artifact.write_text")) return writeText(lease, call, args);
        if (name.equals("input.read_text")) return readText(lease, call, args.getString("inputId"));
        throw new StoreException("FILE_TOOL_UNKNOWN");
    }

    public static JSONArray tools(Set<String> permissions) {
        JSONArray out = new JSONArray();
        for (String name : Arrays.asList("input.read_text", "artifact.write_text"))
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
        return out;
    }

    public void scan() throws Exception {
        ledger.policy();
        disk.tryLocked(
                () -> {
                    ledger.policy();
                    ledger.recoverPins();
                    JSONArray rows = ledger.candidates();
                    if (config.cleanup)
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject claim = ledger.claim(rows.getJSONObject(i).getString("file_id"));
                            if (claim == null) continue;
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
                    return null;
                });
    }

    public JSONObject metrics() {
        return ledger
                .metrics()
                .put("lastError", lastError == null ? JSONObject.NULL : lastError)
                .put("cleanupEnabled", config.cleanup);
    }

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
