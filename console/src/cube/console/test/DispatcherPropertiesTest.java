package cube.console.test;

import cube.console.mgmt.DirectorProperties;
import cube.console.mgmt.DispatcherProperties;
import cube.console.mgmt.DispatcherServer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Old and new configurations remain editable after removing routing weights. */
public final class DispatcherPropertiesTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("cube-director-config-");
        Path config = directory.resolve("dispatcher.properties");
        Files.write(config, ("cellets=AIGC\ndirector.1.address=127.0.0.1\ndirector.1.port=6000\n"
                + "director.1.cellets=AIGC\ndirector.1.weight=invalid-old-value\ndirector.49.weight=9\n").getBytes(StandardCharsets.UTF_8));
        DispatcherProperties properties = new DispatcherProperties(config.toString()); properties.load();
        check(properties.getDirectorProperties().size() == 1, "obsolete weights not parsed");
        check(!properties.getDirectorProperties().get(0).toJSON().has("weight"), "weight omitted in API response");
        DirectorProperties fromClient = new DirectorProperties(new JSONObject().put("address", "127.0.0.1")
                .put("port", 6000).put("cellets", new JSONArray().put("AIGC")).put("weight", "ignored"));
        check(!properties.updateDirectorProperties(java.util.Collections.singletonList(fromClient)), "old client weight ignored in comparisons");
        DispatcherServer server = new DispatcherServer("test", directory.toString(), "unused-cell.xml", config.toString());
        java.lang.reflect.Field field = DispatcherServer.class.getDeclaredField("propertiesFile");
        field.setAccessible(true); field.set(server, properties);
        // Saving identical values must still migrate the old configuration.
        server.updateCellConfig(new JSONObject().put("directors", new JSONArray().put(fromClient.toJSON())));
        check(!new String(Files.readAllBytes(config), StandardCharsets.ISO_8859_1).contains("weight"), "all obsolete weight keys removed");
        DispatcherProperties reloaded = new DispatcherProperties(config.toString()); reloaded.load();
        check(reloaded.getDirectorProperties().size() == 1, "configuration without weight reloads");
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
        System.out.println("DispatcherPropertiesTest: 5 assertions passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
