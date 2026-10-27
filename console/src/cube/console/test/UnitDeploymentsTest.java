package cube.console.test;

import cell.core.talk.TalkContext;
import cube.common.entity.AICapability;
import cube.common.entity.AIGCUnit;
import cube.common.entity.Contact;
import cube.common.entity.Device;
import cube.console.Console;
import cube.console.container.handler.StatisticDataHandler;
import cube.console.mgmt.UnitDeploymentSnapshot;
import cube.console.mgmt.UserManager;
import cube.report.UnitReport;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;

/** 当前部署的采集、跨节点归并、过期及真实 HTTP 路由回归；不需要统计库。 */
public final class UnitDeploymentsTest {

    private static int assertions;
    private static final String DOMAIN = "units.example";

    public static void main(String[] args) throws Exception {
        collection();
        grouping();
        snapshotsAndHttp();
        System.out.println("UnitDeploymentsTest: " + assertions + " assertions passed");
    }

    private static void collection() {
        Contact contact = new Contact(100001L, DOMAIN, "worker-a");
        contact.addDevice(new Device("GPU-A", "Linux"));
        TalkContext context = new TalkContext(null, null) {
            @Override public boolean isValid() { return true; }
            @Override public String getSessionHost() { return "2001:db8::1"; }
        };
        AIGCUnit unit = new AIGCUnit(contact, capability("Baize", "1.0"), context);
        UnitReport report = new UnitReport("node-a");
        check(report.addUnit(unit), "collect connected deployment");
        unit.setContext(null);
        report.addUnit(unit);
        unit.setContext(new TalkContext(null, null) {
            @Override public boolean isValid() { return false; }
        });
        report.addUnit(unit);
        UnitReport decoded = new UnitReport(report.toJSON());
        JSONObject item = decoded.getUnits().get(0);
        check(item.getString("address").equals("2001:db8::1"), "IP is actual context peer");
        check(item.getString("device").equals("GPU-A") && item.getString("platform").equals("Linux"), "device metadata preserved");
        check(item.getJSONObject("capability").getJSONObject("chatCompletion").getBoolean("stream"), "protocol declarations preserved");
        check(decoded.numUnits() == 3 && !decoded.getUnits().get(1).getBoolean("connected")
                && !decoded.getUnits().get(2).getBoolean("connected"), "null/invalid connections retained for old ledger only");
        UnitDeploymentSnapshot snapshot = new UnitDeploymentSnapshot(DOMAIN, 1000);
        snapshot.append("node-a", decoded, 900);
        check(group(snapshot.toJSON(), "Baize").getInt("deploymentCount") == 1, "only connected deployment advertised");
        UnitDeploymentSnapshot invalid = new UnitDeploymentSnapshot(DOMAIN, 1000);
        invalid.append("node-a", report("node-a", decoded.getUnits().get(1), decoded.getUnits().get(2)), 900);
        check(invalid.toJSON().getJSONArray("groups").length() == 0, "invalid connections alone produce empty deployment list");

        UnitReport noDevice = new UnitReport("node-a");
        noDevice.addUnit(new AIGCUnit(new Contact(100002L, DOMAIN, "unlabelled"), unit.getCapability(), context));
        check(noDevice.getUnits().get(0).optString("device").isEmpty()
                && noDevice.getUnits().get(0).optString("platform").isEmpty(), "missing device metadata stays empty for -- display");

        UnitReport limited = new UnitReport("node-a");
        for (int i = 0; i < UnitReport.MAX_UNITS; ++i) limited.addUnit(contact, unit.getCapability());
        check(!limited.isTruncated(), "exactly 500 entries are complete");
        check(!limited.addUnit(contact, unit.getCapability()), "501st entry rejected");
        check(new UnitReport(limited.toJSON()).isTruncated(), "truncation survives serialization");
    }

    private static void grouping() {
        long now = 1000000;
        JSONObject first = item(100001, DOMAIN, "Baize", "1.0", "10.0.0.1");
        JSONObject newer = item(100001, DOMAIN, "Baize", "2.0", "2001:db8::1");
        JSONObject sameIp = item(100002, DOMAIN, "Baize", "1.0", "10.0.0.1");
        JSONObject baize2 = item(100001, DOMAIN, "Baize2", "1.0", "10.0.0.1");
        JSONObject anotherBaize2 = item(100003, DOMAIN, "Baize2", "1.0", "10.0.0.3");
        JSONObject otherDomain = item(100004, "other.example", "Baize", "1.0", "10.0.0.4");
        UnitDeploymentSnapshot snapshot = new UnitDeploymentSnapshot(DOMAIN, now);
        snapshot.append("node-b", report("node-b", first, newer), now - 500);
        snapshot.append("node-a", report("node-a", first, sameIp, baize2, anotherBaize2, otherDomain), now - 1000);
        JSONObject data = snapshot.toJSON();
        check(data.getJSONArray("groups").length() == 2, "separate exact unit names, isolate domains");
        check(data.getJSONArray("groups").getJSONObject(0).getString("name").equals("Baize"), "names sorted");
        JSONObject baize = group(data, "Baize");
        check(baize.getInt("deploymentCount") == 2, "same identity on two nodes counted once, shared IP identities remain distinct");
        check(group(data, "Baize2").getInt("deploymentCount") == 2, "same contact can also deploy a different unit name");
        JSONObject deployment = baize.getJSONArray("deployments").getJSONObject(0);
        check(deployment.getJSONArray("reporters").toString().equals("[\"node-a\",\"node-b\"]"), "all service nodes preserved and sorted");
        check(deployment.getJSONArray("addresses").length() == 2, "all distinct peer IPs preserved");
        check(deployment.getJSONArray("capabilities").length() == 2, "duplicates removed while versions preserved");
        check(deployment.getLong("reportedAt") == now - 500, "latest console reception time wins");

        JSONObject reordered = new JSONObject().put("description", "test 1.0")
                .put("version", "1.0").put("subtask", "Conversational")
                .put("task", "NaturalLanguageProcessing").put("name", "Baize")
                .put("chatCompletion", new JSONObject().put("stream", true).put("text", true));
        UnitDeploymentSnapshot exact = new UnitDeploymentSnapshot(DOMAIN, now);
        exact.append("a", report("a", first, new JSONObject(first.toString()).put("capability", reordered),
                item(100001, DOMAIN, "baize", "1.0", "10.0.0.1")), now - 100);
        check(group(exact.toJSON(), "Baize").getJSONArray("capabilities").length() == 1, "object key order does not duplicate capabilities");
        check(exact.toJSON().getJSONArray("groups").length() == 2, "unit name grouping is case sensitive");

        JSONObject legacy = item(100010, DOMAIN, "LegacyChat", "1.0", "");
        legacy.remove("connected"); legacy.remove("address"); legacy.remove("device"); legacy.remove("platform");
        UnitReport old = report("legacy", legacy);
        JSONObject oldJson = old.toJSON(); oldJson.remove("truncated");
        snapshot.append("legacy", new UnitReport(oldJson), now - 2000);
        JSONObject legacyRow = group(snapshot.toJSON(), "LegacyChat").getJSONArray("deployments").getJSONObject(0);
        check(legacyRow.getJSONArray("addresses").length() == 0 && legacyRow.getString("device").isEmpty(), "legacy metadata remains unknown");
        check(legacyRow.getJSONArray("capabilities").length() == 1, "legacy identity and capability still visible");

        UnitReport truncated = new UnitReport(new JSONObject().put("reporter", "full").put("name", UnitReport.NAME)
                .put("timestamp", 0).put("truncated", true).put("units", new JSONArray().put(first)));
        UnitDeploymentSnapshot expired = new UnitDeploymentSnapshot(DOMAIN, now);
        expired.append("full", truncated, now - UnitDeploymentSnapshot.TTL - 1);
        check(expired.toJSON().getJSONArray("groups").length() == 0 && !expired.toJSON().getBoolean("truncated"), "expired deployments and truncation discarded");
        expired.append("full", truncated, now - UnitDeploymentSnapshot.TTL);
        check(expired.toJSON().getBoolean("truncated") && expired.toJSON().getJSONArray("groups").length() == 1, "TTL boundary accepted with explicit partial flag");
        UnitDeploymentSnapshot empty = new UnitDeploymentSnapshot(DOMAIN, now);
        empty.append("empty", new UnitReport("empty"), now - 100);
        check(empty.toJSON().getLong("unitReportTime") == now - 100, "empty report updates latest valid report time");
    }

    private static void snapshotsAndHttp() throws Exception {
        Console console = new Console();
        UnitReport first = report("node-a", item(100001, DOMAIN, "Baize", "1.0", "10.0.0.1"));
        console.appendUnitReport(first);
        check(group(console.queryUnitDeployments(DOMAIN), "Baize").getInt("deploymentCount") == 1, "no statistic manager/database needed");
        console.appendUnitReport(new UnitReport("node-a"));
        check(console.queryUnitDeployments(DOMAIN).getJSONArray("groups").length() == 0, "empty node report replaces old deployment");
        check(console.queryUnitCapabilities(DOMAIN).isEmpty(), "empty report also clears original capabilities");
        JSONObject disconnected = item(100001, DOMAIN, "Baize", "1.0", "10.0.0.1").put("connected", false);
        console.appendUnitReport(report("node-a", disconnected));
        check(console.queryUnitDeployments(DOMAIN).getJSONArray("groups").length() == 0
                && console.queryUnitCapabilities(DOMAIN).get(100001L).size() == 1, "original ledger still sees disconnected registered capability");
        console.appendUnitReport(first);

        Field snapshots = Console.class.getDeclaredField("serverUnitMap");
        snapshots.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> reports = (Map<String, Object>) snapshots.get(console);
        Constructor<?> snapshotConstructor = reports.get("node-a").getClass().getDeclaredConstructor(UnitReport.class, long.class);
        snapshotConstructor.setAccessible(true);
        reports.put("node-a", snapshotConstructor.newInstance(first, System.currentTimeMillis() - UnitDeploymentSnapshot.TTL - 1000));
        check(console.queryUnitDeployments(DOMAIN).getJSONArray("groups").length() == 0 && reports.isEmpty(), "console removes expired node snapshot");
        console.appendUnitReport(first);

        Field userManager = Console.class.getDeclaredField("userManager");
        userManager.setAccessible(true);
        userManager.set(console, new UserManager() {
            @Override public boolean checkToken(String token) { return "valid".equals(token); }
        });
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(new StatisticDataHandler(console));
        server.start();
        try {
            check(request(connector, "?domain=" + DOMAIN, null).startsWith("HTTP/1.1 401"), "HTTP query requires cookie");
            check(request(connector, "?domain=" + DOMAIN, "expired").startsWith("HTTP/1.1 401"), "expired cookie rejected");
            check(request(connector, "", "valid").startsWith("HTTP/1.1 400"), "missing domain rejected");
            check(request(connector, "?domain=%20%20", "valid").startsWith("HTTP/1.1 400"), "blank domain rejected");
            String response = request(connector, "?domain=" + DOMAIN, "valid");
            check(response.startsWith("HTTP/1.1 200") && response.contains("\"Baize\""), "authenticated live deployments returned");
            String empty = request(connector, "?domain=no-history.example", "valid");
            check(empty.startsWith("HTTP/1.1 200") && empty.contains("\"groups\":[]"), "domain with no history returns empty 200");
        } finally {
            server.stop();
            server.join();
        }
    }

    private static String request(LocalConnector connector, String query, String token) throws Exception {
        return connector.getResponse("GET /statistic/unit-deployments" + query + " HTTP/1.1\r\nHost: localhost\r\n"
                + (token == null ? "" : "Cookie: CubeConsoleToken=" + token + "\r\n")
                + "Connection: close\r\n\r\n");
    }

    private static AICapability capability(String name, String version) {
        AICapability capability = new AICapability(name, "NaturalLanguageProcessing", version,
                Arrays.asList("Conversational"), "test " + version);
        capability.setChatCompletion(new JSONObject().put("text", true).put("stream", true));
        return capability;
    }

    private static JSONObject item(long id, String domain, String name, String version, String address) {
        return new JSONObject().put("id", id).put("domain", domain).put("name", "worker-" + id)
                .put("connected", true).put("address", address).put("device", "GPU").put("platform", "Linux")
                .put("capability", capability(name, version).toJSON());
    }

    private static UnitReport report(String reporter, JSONObject... items) {
        UnitReport report = new UnitReport(reporter);
        for (JSONObject item : items) report.getUnits().add(item);
        return new UnitReport(report.toJSON());
    }

    private static JSONObject group(JSONObject response, String name) {
        JSONArray groups = response.getJSONArray("groups");
        for (int i = 0; i < groups.length(); ++i) {
            if (groups.getJSONObject(i).getString("name").equals(name)) return groups.getJSONObject(i);
        }
        throw new AssertionError("Missing unit name: " + name);
    }

    private static void check(boolean value, String message) {
        ++assertions;
        if (!value) throw new AssertionError(message);
    }
}
