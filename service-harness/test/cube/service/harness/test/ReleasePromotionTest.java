package cube.service.harness.test;

import cube.common.action.HarnessAction;
import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import static cube.service.harness.test.RuntimeTest.*;

/** M5: signed remote publication, two tenants, real policy CAS and production immutable run pins. */
public final class ReleasePromotionTest {
    private static RemoteAdmin admin;
    private static PrivateKey signingKey;
    private static JSONObject publish(ProfileRegistry.Identity who, byte[] zip) throws Exception {
        JSONObject validated = admin.handle(who, HarnessAction.ValidatePackage, new JSONObject().put("contentType", "application/zip"), zip);
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(signingKey);
        signature.update(validated.getString("digest").getBytes("US-ASCII"));
        return admin.handle(who, HarnessAction.PublishRelease, new JSONObject().put("contentType", "application/zip")
                .put("key", key()).put("signer", "promotion-author").put("signature", Base64.getEncoder().encodeToString(signature.sign())), zip);
    }
    private static JSONObject bind(String requestKey, String target, long version) throws Exception {
        return admin.handle(identity, HarnessAction.BindPolicy, new JSONObject().put("key", requestKey),
                bytes(new JSONObject().put("target", target).put("expectedVersion", version).toString()));
    }
    private static JSONObject policy(JSONObject value) throws Exception {
        return admin.handle(identity, HarnessAction.PublishPolicy, new JSONObject().put("key", key()), bytes(value.toString()));
    }
    private static String selected(ProfileRegistry registry) {
        String run = registry.createRun(identity, key(), request("M5 local publication fixture").put("skills", new JSONArray().put("test/guide"))).getString("runId");
        return run;
    }
    private static String pinned(CatalogStore c, String run) { return c.pinned(T, S, run).getJSONArray("releases").getJSONObject(0).getString("digest"); }
    public static void main(String[] args) throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-m5-promotion-"), "");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair(); signingKey = keys.getPrivate();
        PackageRepository signed = new PackageRepository(catalog, new AssetFiles(Files.createDirectory(root.resolve("signed")), 8000000, "promotion-repository"),
                Collections.singletonMap("promotion-author", keys.getPublic()), false);
        Properties settings = new Properties();
        String testTenant = "promotion-test-tenant";
        settings.setProperty("admin.tenant." + testTenant + ".signers", "promotion-author");
        settings.setProperty("admin.tenant." + T + ".signers", "promotion-author");
        admin = new RemoteAdmin(catalog, signed, new ProviderStore(store, false), new ProfileRegistry(catalog), null, settings, TEST_PERMISSIONS);
        ProfileRegistry.Identity testIdentity = new ProfileRegistry.Identity(testTenant, S, TEST_PERMISSIONS);
        Map<String, byte[]> original = entries("M5 original version"), candidate = entries("M5 candidate version");
        byte[] firstZip = zip(manifest(original).put("version", "m5-1"), original);
        byte[] nextZip = zip(manifest(candidate).put("version", "m5-2"), candidate);
        String old = publish(testIdentity, firstZip).getString("digest");
        check(publish(identity, firstZip).getString("digest").equals(old), "same signed ZIP preserved across tenants");
        String next = publish(testIdentity, nextZip).getString("digest");
        JSONObject task = task("m5", old, "scripted", 1024);
        profiles.configure(settings, T, task, root.resolve("m5-execution"));
        ProfileRegistry registry = new ProfileRegistry(catalog, null, ExecutionProfiles.load(settings));
        String oldRun = selected(registry);
        String oldPin = JdbcRunStore.canonical(catalog.pinned(T, S, oldRun));
        JSONObject base = catalog.tenantPolicy(T), before = catalog.binding(T, "policy", "default");
        check(publish(identity, nextZip).getString("digest").equals(next), "promotion has identical manifest digest");
        check(Arrays.equals(signed.export(testTenant, next), signed.export(T, next)), "both tenants export identical content");
        JSONObject desired = new JSONObject(base.toString()).put("version", "m5-next").put("skillReleases", new JSONArray().put(next));
        policy(desired);
        check(catalog.binding(T, "policy", "default").similar(before), "publication does not activate");
        String operation = key();
        JSONObject switched = bind(operation, "default@m5-next", before.getLong("version"));
        check(bind(operation, "default@m5-next", before.getLong("version")).similar(switched), "CAS same-key replay returns original receipt");
        fail("IDEMPOTENCY_CONFLICT", () -> bind(operation, before.getString("target"), switched.getLong("version")));
        fail("VERSION_CONFLICT", () -> bind(key(), before.getString("target"), before.getLong("version")));
        String newRun = selected(registry);
        check(pinned(catalog, oldRun).equals(old) && pinned(catalog, newRun).equals(next), "switch changes only new run pins");
        JSONObject rolledBack = bind(key(), before.getString("target"), switched.getLong("version"));
        String rollbackRun = selected(registry);
        check(pinned(catalog, rollbackRun).equals(old) && pinned(catalog, newRun).equals(next), "rollback keeps candidate run fixed");
        check(bind(operation, "default@m5-next", before.getLong("version")).similar(switched)
                && catalog.binding(T, "policy", "default").similar(rolledBack), "historical ACK replay cannot undo later rollback");
        CatalogStore reopened = new CatalogStore(new JdbcRunStore(config), false);
        check(JdbcRunStore.canonical(reopened.pinned(T, S, oldRun)).equals(oldPin), "full in-flight pin survives publication, rollback and reopen");
        check(pinned(reopened, newRun).equals(next) && reopened.binding(T, "policy", "default").similar(rolledBack), "reopened store retains pins and current binding");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long expected = rolledBack.getLong("version");
            List<Future<String>> results = pool.invokeAll(Arrays.asList(
                () -> attempt("default@m5-next", expected), () -> attempt("default@m5-next", expected)));
            List<String> statuses = Arrays.asList(results.get(0).get(), results.get(1).get());
            check(Collections.frequency(statuses, "OK") == 1 && Collections.frequency(statuses, "VERSION_CONFLICT") == 1, "exactly one concurrent operator wins CAS");
        } finally { pool.shutdownNow(); }
        check(catalog.binding(testTenant, "policy", "default").getLong("version") == 0, "target publication never changes source tenant binding");
        System.out.println("PASS M5: signed same-digest promotion across tenants, immutable publication, CAS race/replay/conflict, rollback, pinned in-flight runs and restart. No live environment modified.");
    }
    private static String attempt(String target, long expected) throws Exception {
        try { bind(key(), target, expected); return "OK"; }
        catch (StoreException error) { return error.code; }
    }
}
