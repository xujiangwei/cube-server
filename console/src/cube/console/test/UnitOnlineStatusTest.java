package cube.console.test;

import cube.console.Console;
import cube.console.container.handler.ReportHandler;
import cube.console.container.handler.StatisticDataHandler;
import cube.console.mgmt.StatisticDataManager;
import cube.console.mgmt.UnitDeploymentSnapshot;
import cube.console.storage.DataStorage;
import cube.report.UnitReport;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Unit 在线状态的 HTTP 回归：使用真实上报、查询与快照过期逻辑，不连接数据库。 */
public final class UnitOnlineStatusTest {
    private static final String DOMAIN = "units.example";
    private static final long HOUR = 60L * 60 * 1000;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        longLivedWorkers();
        connectionStates();
        multipleNodes();
        expiredReports();
        legacyReports();
        System.out.println("UnitOnlineStatusTest: " + assertions + " assertions passed");
    }

    private static void longLivedWorkers() throws Exception {
        try (Harness h = new Harness()) {
            UnitReport report = report("node-a", item(100001, true), item(100002, true), item(100003, true));
            // 节点时钟有偏差时仍使用 console 的接收时间判断新鲜度。
            JSONObject json = report.toJSON().put("timestamp", 1L);
            h.post(json);
            JSONObject online = h.overview();
            counts(online, 3, 0, 0);
            for (long id = 100001; id <= 100003; ++id) {
                state(online, id, "online");
                check(unit(online, id).getLong("lastActiveTime") == h.storage.signInTime,
                        "live state does not rewrite historical last activity");
                check(unit(online, id).getJSONArray("capabilities").length() == 1,
                        "connected capability retained");
            }
            check(online.getLong("unitReportTime") > System.currentTimeMillis() - HOUR,
                    "report freshness uses console reception clock");

            h.post(report("node-a").toJSON());
            JSONObject offline = h.overview();
            counts(offline, 0, 3, 0);
            for (String key : Arrays.asList("total", "activeCount", "totalDuration", "avgDuration", "timeline", "peak")) {
                check(online.get(key).toString().equals(offline.get(key).toString()),
                        "connection change preserves historical " + key);
            }
            for (long id = 100001; id <= 100003; ++id) {
                state(offline, id, "offline");
                check(unit(offline, id).getJSONArray("capabilities").length() == 0,
                        "empty report clears previous capabilities and online state");
            }
        }
    }

    private static void connectionStates() throws Exception {
        try (Harness h = new Harness()) {
            h.storage.events.clear();
            h.storage.events.add(event(100002, "DeviceTimeout", System.currentTimeMillis() - HOUR));
            h.storage.events.add(event(100003, "SignOut", System.currentTimeMillis() - HOUR));
            h.post(report("node-a", item(100001, true), item(100002, false), item(100003, false)).toJSON());
            JSONObject data = h.overview();
            counts(data, 1, 2, 0);
            state(data, 100001, "online");
            check(unit(data, 100001).getLong("lastActiveTime") == 0,
                    "fresh connected worker is online even without historical events");
            state(data, 100002, "offline");
            state(data, 100003, "offline");
            check(unit(data, 100002).getJSONArray("capabilities").length() == 1,
                    "a disconnected capability does not imply online");
            h.post(report("node-a").toJSON());
            data = h.overview();
            counts(data, 0, 2, 1);
            state(data, 100001, "inactive");
            h.post(report("node-a", item(100002, true), item(100003, true)).toJSON());
            data = h.overview();
            counts(data, 2, 0, 1);
            state(data, 100002, "online");
            state(data, 100003, "online");
            h.storage.events.clear();
            for (long id = 100001; id <= 100003; ++id) {
                h.storage.events.add(event(id, "SignIn", System.currentTimeMillis() - HOUR));
            }
            h.post(report("node-a").toJSON());
            counts(h.overview(), 0, 3, 0);
        }
    }

    private static void multipleNodes() throws Exception {
        try (Harness h = new Harness()) {
            h.post(report("node-a", item(100001, false), item(100001, true).put("capability",
                    new JSONObject().put("name", "AnotherChat"))).toJSON());
            h.post(report("node-b", item(100001, false), item(100002, true)).toJSON());
            JSONObject data = h.overview();
            counts(data, 2, 1, 0);
            state(data, 100001, "online");
            check(unit(data, 100001).getJSONArray("capabilities").length() == 2,
                    "multiple capabilities still describe one physical worker");

            h.post(report("node-a", item(100001, false)).toJSON());
            data = h.overview();
            counts(data, 1, 2, 0);
            state(data, 100001, "offline");
            h.post(report("node-a", item(100001, true)).toJSON());
            h.post(report("node-b").toJSON());
            data = h.overview();
            counts(data, 1, 2, 0);
            state(data, 100001, "online");
            state(data, 100002, "offline");

            h.post(report("node-a", item(100001, true).put("domain", "other.example")).toJSON());
            counts(h.overview(), 0, 3, 0);
        }
    }

    private static void expiredReports() throws Exception {
        try (Harness h = new Harness()) {
            h.post(report("node-a", item(100001, true), item(100002, true), item(100003, true)).toJSON());
            h.expire("node-a");
            h.post(report("node-b", item(100001, false)).toJSON());
            JSONObject data = h.overview();
            counts(data, 0, 3, 0);
            state(data, 100001, "offline");
            check(unit(data, 100002).getJSONArray("capabilities").length() == 0,
                    "expired report clears capabilities together with online state");
            h.expire("node-b");
            data = h.overview();
            counts(data, 0, 3, 0);
            check(data.getLong("unitReportTime") == 0, "expired snapshots no longer advertise fresh reports");
            h.post(report("node-a", item(100001, true), item(100002, true), item(100003, true)).toJSON());
            counts(h.overview(), 3, 0, 0);
        }
    }

    private static void legacyReports() throws Exception {
        try (Harness h = new Harness()) {
            JSONObject legacy = item(100001, true);
            legacy.remove("connected");
            h.post(report("node-a", legacy).toJSON());
            counts(h.overview(), 1, 2, 0);
            h.expire("node-a");
            counts(h.overview(), 0, 3, 0);
        }
    }

    private static UnitReport report(String reporter, JSONObject... items) {
        UnitReport report = new UnitReport(reporter);
        report.getUnits().addAll(Arrays.asList(items));
        return report;
    }

    private static JSONObject item(long id, boolean connected) {
        return new JSONObject().put("id", id).put("domain", DOMAIN).put("name", "worker-" + id)
                .put("connected", connected).put("capability", new JSONObject().put("name", "ProbeChat"));
    }

    private static DataStorage.ContactEvent event(long id, String name, long time) throws Exception {
        Constructor<DataStorage.ContactEvent> constructor = DataStorage.ContactEvent.class
                .getDeclaredConstructor(long.class, String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, name, time);
    }

    private static JSONObject unit(JSONObject data, long id) {
        JSONArray units = data.getJSONArray("units");
        for (int i = 0; i < units.length(); ++i) {
            JSONObject unit = units.getJSONObject(i);
            if (unit.getLong("id") == id) return unit;
        }
        throw new AssertionError("Missing unit " + id);
    }

    private static void state(JSONObject data, long id, String expected) {
        check(expected.equals(unit(data, id).getString("state")), "worker " + id + " should be " + expected);
    }

    private static void counts(JSONObject data, int online, int offline, int inactive) {
        check(data.getInt("total") == 3, "three physical workers in ledger");
        check(data.getInt("onlineCount") == online,
                "online count should be " + online + ", got " + data.getInt("onlineCount"));
        check(data.getInt("offlineCount") == offline, "offline count should be " + offline);
        check(data.getInt("inactiveCount") == inactive, "inactive count should be " + inactive);
    }

    private static void check(boolean condition, String message) {
        ++assertions;
        if (!condition) throw new AssertionError(message);
    }

    private static final class FixtureStorage extends DataStorage {
        final long signInTime = System.currentTimeMillis() - 25 * HOUR;
        final List<ContactEvent> events = new ArrayList<>();

        FixtureStorage() throws Exception {
            super(new Properties());
            for (long id = 100001; id <= 100003; ++id) events.add(event(id, "SignIn", signInTime));
        }

        @Override public List<JSONObject> queryUnitContacts(String domain) {
            List<JSONObject> contacts = new ArrayList<>();
            for (long id = 100001; id <= 100003; ++id) contacts.add(new JSONObject().put("id", id)
                    .put("name", "worker-" + id).put("device", "GPU").put("platform", "Linux"));
            return contacts;
        }

        @Override public List<ContactEvent> queryContactEvents(String domain, long from, long to, boolean unitOnly) {
            List<ContactEvent> result = new ArrayList<>();
            for (ContactEvent event : events) if (event.time >= from && event.time < to) result.add(event);
            return result;
        }
    }

    private static final class Harness implements AutoCloseable {
        final FixtureStorage storage = new FixtureStorage();
        final Console console = new Console();
        final Server server = new Server();
        final LocalConnector connector = new LocalConnector(server);

        Harness() throws Exception {
            StatisticDataManager manager = new StatisticDataManager();
            Field field = StatisticDataManager.class.getDeclaredField("storage");
            field.setAccessible(true); field.set(manager, storage);
            field = Console.class.getDeclaredField("statisticDataManager");
            field.setAccessible(true); field.set(console, manager);
            server.addConnector(connector);
            HandlerList handlers = new HandlerList();
            handlers.addHandler(new ReportHandler(console));
            handlers.addHandler(new StatisticDataHandler(console));
            server.setHandler(handlers);
            server.start();
        }

        void post(JSONObject report) throws Exception {
            String body = report.toString();
            String response = connector.getResponse("POST /report HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body);
            check(response.startsWith("HTTP/1.1 200"), "unit report accepted by actual HTTP receiver");
        }

        JSONObject overview() throws Exception {
            String response = connector.getResponse("GET /statistic/units?domain=" + DOMAIN
                    + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            check(response.startsWith("HTTP/1.1 200"), "unit overview returned by actual HTTP query");
            return new JSONObject(response.substring(response.indexOf("\r\n\r\n") + 4));
        }

        @SuppressWarnings("unchecked")
        void expire(String reporter) throws Exception {
            Field field = Console.class.getDeclaredField("serverUnitMap");
            field.setAccessible(true);
            Map<String, Object> snapshots = (Map<String, Object>) field.get(console);
            Object snapshot = snapshots.get(reporter);
            Field reportField = snapshot.getClass().getDeclaredField("report");
            reportField.setAccessible(true);
            Constructor<?> constructor = snapshot.getClass().getDeclaredConstructor(UnitReport.class, long.class);
            constructor.setAccessible(true);
            snapshots.put(reporter, constructor.newInstance(reportField.get(snapshot),
                    System.currentTimeMillis() - UnitDeploymentSnapshot.TTL - 1000));
        }

        @Override public void close() throws Exception {
            server.stop();
            server.join();
        }
    }
}
