package cube.service.harness.execution;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

public final class QueryProviderFactoryTest {
    private static int opened, closed, factoriesClosed;
    public static final class Provider implements QueryProvider {
        private final String id;
        private final boolean invalid, closeFailure;
        Provider(String id) { this(id, false, false); }
        Provider(String id, boolean invalid, boolean closeFailure) { this.id = id; this.invalid = invalid; this.closeFailure = closeFailure; opened++; }
        public String operation() { return id; }
        public String permission() { return "unit.test"; }
        public JSONObject inputSchema() {
            JSONObject schema = new JSONObject("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
            if (invalid) schema.put("$ref", "unsupported.json");
            return schema;
        }
        public JSONObject resultSchema() { return inputSchema(); }
        public void validate(JSONObject args) { }
        public JSONObject query(JSONObject args) { return new JSONObject(); }
        public void validateResult(JSONObject args, JSONObject result) { }
        public void close() { closed++; if (closeFailure) throw new IllegalStateException("CLEANUP_FAILED"); }
    }
    public static final class Factory implements QueryProviderFactory {
        private final String mode;
        public Factory(Properties config) { mode = config.getProperty("mode", "normal"); }
        public Collection<QueryProvider> create() {
            if (mode.equals("failure")) throw new IllegalStateException("FACTORY_FAILED");
            if (mode.equals("invalid")) return Arrays.asList(new Provider("unit.a.v1"),
                    new Provider("unit.b.v1", true, true), new Provider("unit.c.v1", false, true), new Provider("unit.d.v1"));
            return Arrays.asList(new Provider("unit.a.v1"), new Provider(mode.equals("duplicate") ? "unit.a.v1" : "unit.b.v1"), new Provider("unit.c.v1"));
        }
        public void close() { factoriesClosed++; }
    }
    private static Properties config(Path file) {
        Properties result = new Properties();
        result.setProperty("broker.providers", "units");
        result.setProperty("broker.provider.units.class", Factory.class.getName());
        result.setProperty("broker.provider.units.config", file.toString());
        return result;
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] ignored) throws Exception {
        Path file = Files.createTempFile("unit-factory-", ".properties");
        try {
            Files.write(file, "mode=normal\n".getBytes(StandardCharsets.UTF_8));
            QueryProviders providers = new QueryProviders(config(file));
            check(providers.all().size() == 3 && providers.get("unit.b.v1") != null);
            providers.close(); providers.close();
            check(opened == 3 && closed == 3 && factoriesClosed == 1);
            Files.write(file, "mode=duplicate\n".getBytes(StandardCharsets.UTF_8));
            try { new QueryProviders(config(file)); throw new AssertionError("duplicate accepted"); }
            catch (IllegalArgumentException expected) { check("DUPLICATE_QUERY_PROVIDER".equals(expected.getMessage())); }
            check(opened == 6 && closed == 6 && factoriesClosed == 2);
            Files.write(file, "mode=invalid\n".getBytes(StandardCharsets.UTF_8));
            try { new QueryProviders(config(file)); throw new AssertionError("invalid schema accepted"); }
            catch (RuntimeException expected) {
                check(!"CLEANUP_FAILED".equals(expected.getMessage()) && expected.getSuppressed().length > 0);
            }
            check(opened == 10 && closed == 10 && factoriesClosed == 3);
            Files.write(file, "mode=failure\n".getBytes(StandardCharsets.UTF_8));
            try { new QueryProviders(config(file)); throw new AssertionError("factory failure accepted"); }
            catch (IllegalStateException expected) { check("FACTORY_FAILED".equals(expected.getMessage())); }
            check(opened == 10 && closed == 10 && factoriesClosed == 4);
            System.out.println("QueryProviderFactoryTest passed");
        } finally { Files.deleteIfExists(file); }
    }
}
