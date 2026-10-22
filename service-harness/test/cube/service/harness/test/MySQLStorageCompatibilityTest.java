package cube.service.harness.test;

import cell.core.talk.LiteralBase;
import cube.core.Conditional;
import cube.core.Constraint;
import cube.core.Storage;
import cube.core.StorageField;
import cube.storage.MySQLStorage;
import cube.storage.StorageFactory;
import cube.storage.StorageType;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Exercises the existing Storage/ConnectionHandler APIs on a disposable MySQL database. */
public final class MySQLStorageCompatibilityTest {
    private static final String TABLE = "harness_storage_compat";
    private static final String TAGS = "harness_storage_compat_tags";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static StorageField[] row(int id, String name) {
        return new StorageField[] { new StorageField("id", LiteralBase.INT, id),
                new StorageField("name", LiteralBase.STRING, name) };
    }

    private static void crud(Storage storage) {
        check(!storage.exist(TABLE), "Fresh compatibility table already exists");
        check(storage.executeCreate(TABLE, new StorageField[] {
                new StorageField("id", LiteralBase.INT, new Constraint[] { Constraint.PRIMARY_KEY }),
                new StorageField("name", LiteralBase.STRING) }), "Create failed");
        check(storage.exist(TABLE), "Created table missing");
        check(storage.executeInsert(TABLE, row(1, "first")), "Single insert failed");
        check(storage.executeInsert(TABLE, Arrays.asList(row(2, "second"), row(3, "third"))), "Batch insert failed");
        StorageField[] fields = { new StorageField("id", LiteralBase.INT), new StorageField("name", LiteralBase.STRING) };
        check(storage.executeQuery(TABLE, fields).size() == 3, "Typed query changed");
        Conditional[] id = { Conditional.createEqualTo("id", 1) };
        check(storage.executeUpdate(TABLE, new StorageField[] {
                new StorageField("name", LiteralBase.STRING, "updated") }, id), "Update failed");
        check(storage.executeQuery(TABLE, fields, id).get(0)[1].getString().equals("updated"), "Filtered query changed");
        check(storage.executeQuery("SELECT id,name FROM " + TABLE + " WHERE id=1").get(0)[1].getString().equals("updated"),
                "Raw query changed");
        check(storage.execute("SELECT 1"), "Raw execute SELECT result changed");
        check(!storage.execute("UPDATE " + TABLE + " SET name='third-updated' WHERE id=3"),
                "Raw execute UPDATE result changed");
        check(storage.executeDelete(TABLE, new Conditional[] { Conditional.createEqualTo("id", 2) }), "Delete failed");
        check(storage.executeQuery(TABLE, fields).size() == 2, "Delete did not persist");
        check(storage.executeCreate(TAGS, new StorageField[] { new StorageField("owner_id", LiteralBase.INT),
                new StorageField("tag", LiteralBase.STRING) }), "Join table create failed");
        check(storage.executeInsert(TAGS, new StorageField[] { new StorageField("owner_id", LiteralBase.INT, 1),
                new StorageField("tag", LiteralBase.STRING, "joined") }), "Join row insert failed");
        List<StorageField[]> joined = storage.executeQuery(new String[] { TABLE, TAGS }, new StorageField[] {
                new StorageField(TABLE, "id", LiteralBase.INT), new StorageField(TAGS, "tag", LiteralBase.STRING) },
                new Conditional[] { Conditional.createEqualTo(new StorageField(TABLE, "id", LiteralBase.INT),
                        new StorageField(TAGS, "owner_id", LiteralBase.INT)) });
        check(joined.size() == 1 && joined.get(0)[1].getString().equals("joined"), "Multi-table query changed");
        System.out.println("PASS: factory, create/exist, single/batch insert, typed/raw/join queries, update/delete and execute results");
    }

    private static void callbacks(MySQLStorage storage) {
        AtomicLong physical = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException marker = new IllegalStateException("callback-probe");
        // Exceed the pool limit in sequence: exceptions must return connections instead of exhausting the pool.
        for (int i = 0; i < 70; i++) {
            try {
                storage.execute(connection -> {
                    try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT CONNECTION_ID()")) {
                        r.next();
                        long current = r.getLong(1);
                        if (physical.get() == 0) physical.set(current);
                        check(physical.get() == current && connection.getAutoCommit(), "Callback connection reuse/state changed");
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    calls.incrementAndGet();
                    throw marker;
                });
                throw new AssertionError("Callback exception was swallowed");
            } catch (IllegalStateException e) {
                check(e == marker, "Callback exception changed");
            }
        }
        check(calls.get() == 70, "Callbacks were skipped");
        System.out.println("PASS: existing ConnectionHandler callback, exception propagation and connection return");
    }

    private static void concurrent(Storage storage) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 8; i++) work.add(executor.submit(() -> {
                for (int j = 0; j < 20; j++) {
                    check(storage.executeQuery(TABLE, new StorageField[] { new StorageField("id", LiteralBase.INT) }).size() == 2,
                            "Concurrent legacy query changed");
                }
            }));
            for (Future<?> future : work) future.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        System.out.println("PASS: concurrent calls through existing Storage API");
    }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(System.getProperty("store.test.config")))) {
            properties.load(in);
        }
        check("true".equals(properties.getProperty("store.test.disposable")), "Disposable database required");
        JSONObject config = new JSONObject()
                .put(MySQLStorage.CONFIG_HOST, properties.getProperty("store.mysql.host"))
                .put(MySQLStorage.CONFIG_PORT, Integer.parseInt(properties.getProperty("store.mysql.port", "3306")))
                .put(MySQLStorage.CONFIG_SCHEMA, properties.getProperty("store.database"))
                .put(MySQLStorage.CONFIG_USER, properties.getProperty("store.mysql.user"))
                .put(MySQLStorage.CONFIG_PASSWORD, properties.getProperty("store.mysql.password"));
        Storage storage = StorageFactory.getInstance().createStorage(StorageType.MySQL, "CompatibilityProbe", config);
        check(StorageFactory.getInstance().getStorage("CompatibilityProbe") == storage
                && StorageFactory.getInstance().getStorageType(storage) == StorageType.MySQL, "Storage factory contract changed");
        storage.open();
        storage.open();
        try {
            crud(storage);
            callbacks((MySQLStorage) storage);
            concurrent(storage);
            storage.close();
            storage.close();
            check(!storage.exist(TABLE) && storage.executeQuery("SELECT 1").isEmpty(), "Closed storage result changed");
            check(storage.executeQuery(TABLE, new StorageField[] { new StorageField("id", LiteralBase.INT) }) == null,
                    "Closed typed-query result changed");
            AtomicInteger called = new AtomicInteger();
            ((MySQLStorage) storage).execute(connection -> called.incrementAndGet());
            check(called.get() == 0, "Closed storage executed callback");
            storage.open();
            check(storage.exist(TABLE), "Storage could not reopen");
            System.out.println("PASS: repeated open/close, unavailable pool results and reopen");
        } finally {
            storage.open();
            storage.execute("DROP TABLE IF EXISTS " + TAGS);
            storage.execute("DROP TABLE IF EXISTS " + TABLE);
            storage.close();
        }
    }
}
