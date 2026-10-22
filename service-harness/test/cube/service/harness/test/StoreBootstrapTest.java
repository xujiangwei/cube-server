package cube.service.harness.test;

import cube.service.harness.*;
import cube.service.harness.execution.DefaultProfiles;
import cube.service.harness.execution.ModelProfiles;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Integration tests against a fresh disposable SQLite or MySQL database. */
public final class StoreBootstrapTest {
    private static Properties properties;
    private static StoreConfig config;
    private static Path root;

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void sql(String text) throws Exception {
        try (Connection c = config.connect(); Statement s = c.createStatement()) { s.execute(text); }
    }
    private static long count(String table, String tenant) throws Exception {
        try (Connection c = config.connect(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE tenant=?")) {
            s.setString(1, tenant);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    public static Path writeTemplates(Path path, String taskId) throws Exception {
        Files.createDirectories(path);
        JSONObject task = DefaultProfiles.task("Baize2").put("id", taskId);
        Files.write(path.resolve("runtime.json"), RuntimeSupport.bytes(DefaultProfiles.runtime()));
        Files.write(path.resolve("model.json"), RuntimeSupport.bytes(ModelProfiles.model("Baize2")));
        Files.write(path.resolve("task.json"), RuntimeSupport.bytes(task));
        return path;
    }
    private static void startup() throws Exception {
        // A reader must never initialize an empty database.
        try { new JdbcRunStore(config); throw new AssertionError("Reader initialized empty store"); }
        catch (StoreException expected) { }
        Path privateConfig = root.resolve("startup.properties");
        try (OutputStream out = Files.newOutputStream(privateConfig)) { properties.store(out, "Disposable test"); }
        if (config.mysql()) {
            Process crash = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), Child.class.getName(), privateConfig.toString(), "--crash")
                    .redirectErrorStream(true).redirectOutput(root.resolve("ddl-crash.log").toFile()).start();
            check(crash.waitFor(30, TimeUnit.SECONDS) && crash.exitValue() == 73, "DDL interruption child failed");
        }
        List<Process> startups = new ArrayList<>();
        for (int i = 0; i < 2; i++) startups.add(new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), privateConfig.toString(), "--startup")
                .redirectErrorStream(true).redirectOutput(root.resolve("startup-child-" + i + ".log").toFile()).start());
        for (Process child : startups) check(child.waitFor(30, TimeUnit.SECONDS) && child.exitValue() == 0,
                "Concurrent JVM startup failed");
        for (int i = 0; i < 2; i++) {
            HarnessService service = new HarnessService(new HarnessConfig(properties));
            try { service.start(); check(service.isStarted() && service.runStore() != null, "Automatic startup failed"); }
            finally { service.stop(); }
        }
        JdbcRunStore store = new JdbcRunStore(config);
        String run = store.create("retained", "user", "startup", "profile", new JSONObject()).getString("runId");
        sql("DROP TABLE harness_tenant");
        sql("DROP INDEX harness_recovery" + (config.mysql() ? " ON harness_run" : ""));
        if (config.mysql()) sql("ALTER TABLE harness_model_job DROP COLUMN finished_at");
        StoreBootstrap.selfCheck(config);
        check(store.read("retained", "user", run).getJSONObject("snapshot").getString("state").equals("queued"), "Repair lost run data");
        // An unrecognized column is a conflict, never automatically rewritten.
        sql("ALTER TABLE harness_tenant ADD COLUMN unexpected INTEGER");
        try { StoreBootstrap.selfCheck(config); throw new AssertionError("Conflicting table accepted"); }
        catch (StoreException e) { check(e.code.equals("STORE_SCHEMA_MISMATCH"), e.code); }
        sql("DROP TABLE harness_tenant");
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 3; i++) work.add(threads.submit(() -> {
                try { StoreBootstrap.selfCheck(config); } catch (Exception e) { throw new RuntimeException(e); }
            }));
            for (Future<?> future : work) future.get(30, TimeUnit.SECONDS);
        } finally { threads.shutdownNow(); }
        System.out.println("PASS: empty/repeated service startup, concurrent JVM construction, missing objects, conflicts and retained data");
    }
    private static void tenantInitialization() throws Exception {
        Path directory = writeTemplates(root.resolve("templates"), "resources-Baize2");
        TenantTemplates templates = TenantTemplates.load(directory);
        JdbcRunStore store = new JdbcRunStore(config);
        CatalogStore catalog = new CatalogStore(store, config.mysql());
        ExecutorService threads = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 6; i++) work.add(threads.submit(() -> catalog.ensureTenant("new.example.com", templates)));
            for (Future<?> future : work) future.get(30, TimeUnit.SECONDS);
        } finally { threads.shutdownNow(); }
        check(count("harness_tenant", "new.example.com") == 1 && count("harness_profile", "new.example.com") == 3
                && count("harness_binding", "new.example.com") == 1, "Concurrent first access duplicated tenant data");
        ProfileRegistry.Identity user = new ProfileRegistry.Identity("new.example.com", "10001", ProfileRegistry.PLATFORM_PERMISSIONS);
        JSONObject request = new JSONObject().put("taskProfileId", "resources-Baize2")
                .put("input", new JSONObject().put("instruction", "hello").put("attachmentIds", new JSONArray()));
        new ProfileRegistry(catalog).createRun(user, "first", "resources-Baize2", request);
        // Existing configuration is adopted even when no task is enabled.
        new ProfileRegistry(catalog).publish("legacy.example.com", DefaultProfiles.runtime());
        catalog.ensureTenant("legacy.example.com", templates);
        check(count("harness_profile", "legacy.example.com") == 1 && catalog.taskProfiles("legacy.example.com").length() == 0,
                "Legacy tenant configuration changed");
        AtomicBoolean fail = new AtomicBoolean(true);
        CatalogStore faulty = new CatalogStore(new JdbcRunStore(config, boundary -> {
            if (boundary.equals("beforeCommit") && fail.getAndSet(false)) throw new StoreException("STORE_UNAVAILABLE");
        }), config.mysql());
        try { faulty.ensureTenant("retry.example.com", templates); throw new AssertionError("Fault was lost"); }
        catch (StoreException expected) { }
        check(count("harness_tenant", "retry.example.com") == 0 && count("harness_profile", "retry.example.com") == 0
                && count("harness_binding", "retry.example.com") == 0, "Partial tenant committed");
        faulty.ensureTenant("retry.example.com", templates);
        TenantTemplates updated = TenantTemplates.load(writeTemplates(directory, "new-task"));
        catalog.ensureTenant("new.example.com", updated);
        catalog.ensureTenant("other.example.com", updated);
        check(catalog.taskProfiles("new.example.com").getJSONObject(0).getString("definition_json").contains("resources-Baize2"), "Existing tenant followed template change");
        check(catalog.taskProfiles("other.example.com").getJSONObject(0).getString("definition_json").contains("new-task"), "New tenant did not use new template");
        catalog.ensureTenant("snapshot.example.com", templates);
        check(catalog.taskProfiles("snapshot.example.com").getJSONObject(0).getString("definition_json").contains("resources-Baize2"), "Startup snapshot was mutable");
        // Cross-JVM first access uses the database's unique row and transaction lock.
        Path privateConfig = root.resolve("bootstrap.properties");
        try (OutputStream out = Files.newOutputStream(privateConfig)) { properties.store(out, "Disposable bootstrap fixture"); }
        List<Process> children = new ArrayList<>();
        for (int i = 0; i < 2; i++) children.add(new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), privateConfig.toString(), directory.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("tenant-child-" + i + ".log").toFile()).start());
        for (Process child : children) check(child.waitFor(30, TimeUnit.SECONDS) && child.exitValue() == 0, "Cross-JVM initialization failed");
        check(count("harness_profile", "process.example.com") == 3 && count("harness_tenant", "process.example.com") == 1,
                "Cross-JVM initialization duplicated data");
        System.out.println("PASS: atomic tenant defaults, direct run, concurrency across JVMs, rollback/retry, legacy adoption, isolation and template snapshots");
    }
    private static void invalidTemplates() throws Exception {
        Path path = writeTemplates(root.resolve("invalid-templates"), "resources-Baize2");
        Files.write(path.resolve("task.json"), RuntimeSupport.bytes(DefaultProfiles.task("Baize2")
                .put("model", new JSONObject().put("id", "missing").put("version", "2"))));
        try { TenantTemplates.load(path); throw new AssertionError("Missing reference accepted"); } catch (IOException expected) { }
        writeTemplates(path, "resources-Baize2");
        Files.copy(path.resolve("model.json"), path.resolve("duplicate.json"));
        try { TenantTemplates.load(path); throw new AssertionError("Duplicate definition accepted"); } catch (IOException expected) { }
        Files.delete(path.resolve("duplicate.json"));
        Files.write(path.resolve("other-task.json"), RuntimeSupport.bytes(DefaultProfiles.task("Baize2").put("version", "3")));
        try { TenantTemplates.load(path); throw new AssertionError("Ambiguous task accepted"); } catch (IOException expected) { }
        Files.delete(path.resolve("other-task.json"));
        Files.write(path.resolve("task.json"), RuntimeSupport.bytes(DefaultProfiles.task("Baize2").put("skillReleases", new JSONArray().put(String.join("", Collections.nCopies(64, "a"))))));
        try { TenantTemplates.load(path); throw new AssertionError("Skill reference accepted"); } catch (IOException expected) { }
        Properties invalid = new Properties(); invalid.putAll(properties);
        invalid.setProperty("execution.enabled", "true");
        invalid.setProperty("execution.repository", Files.createDirectory(root.resolve("invalid-repository")).toString());
        invalid.setProperty("execution.tenant.templates", path.toString());
        HarnessService service = new HarnessService(new HarnessConfig(invalid));
        try { service.start(); check(!service.isStarted() && service.runStore() == null, "Invalid templates did not fail startup"); }
        finally { service.stop(); }
        System.out.println("PASS: invalid template references, duplicates, task versions and skill references rejected before readiness");
    }
    public static final class Child {
        public static void main(String[] args) throws Exception {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Paths.get(args[0]))) { p.load(in); }
            StoreConfig sc = new StoreConfig(p);
            if (args[1].equals("--startup")) {
                HarnessService service = new HarnessService(new HarnessConfig(p));
                try { service.start(); check(service.isStarted() && service.runStore() != null, "Child service startup failed"); }
                finally { service.stop(); }
                return;
            }
            if (args[1].equals("--crash")) {
                try (Connection c = sc.connect(); Statement s = c.createStatement();
                     InputStream in = StoreBootstrapTest.class.getResourceAsStream("/store/mysql.sql")) {
                    String[] ddl = new String(RuntimeSupport.read(in, 262144), "UTF-8").split(";");
                    s.execute(ddl[0]);
                    try (PreparedStatement q = c.prepareStatement("INSERT INTO harness_store_identity VALUES (1,?,?)")) {
                        q.setString(1, sc.identity); q.setString(2, StoreBootstrap.digest(sc)); q.executeUpdate();
                    }
                    s.execute(ddl[1]); s.execute(ddl[2]);
                    Runtime.getRuntime().halt(73);
                }
            }
            new CatalogStore(new JdbcRunStore(sc), sc.mysql()).ensureTenant("process.example.com", TenantTemplates.load(Paths.get(args[1])));
        }
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("harness-bootstrap-");
        properties = new Properties();
        String file = System.getProperty("bootstrap.test.config", "");
        if (!file.isEmpty() && !file.startsWith("${")) {
            try (InputStream in = Files.newInputStream(Paths.get(file))) { properties.load(in); }
            check("true".equals(properties.getProperty("store.test.disposable")), "Disposable database required");
        } else {
            properties.setProperty("store.dialect", "sqlite");
            properties.setProperty("store.database", "harness_bootstrap");
            properties.setProperty("store.identity", "bootstrap-test-identity-123456");
            properties.setProperty("store.sqlite.root", root.toString());
            properties.setProperty("store.sqlite.file", root.resolve("harness_bootstrap.db").toString());
        }
        properties.setProperty("store.enabled", "true");
        properties.setProperty("http.port", "0");
        properties.setProperty("worker.credential", "private-bootstrap-test-worker-1234");
        config = new StoreConfig(properties);
        startup(); tenantInitialization(); invalidTemplates();
        System.out.println("PASS: automatic store and tenant initialization on " + config.dialect);
    }
}
