package cube.service.harness.skill;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Test-classpath-only page transport. Never packaged in the production JAR. */
public final class FixtureWebProvider extends WebPageProvider {
    public FixtureWebProvider(Properties p) {
        super(p, new NetClient() {
            Response get(URI uri, boolean publicOnly, Set<String> hosts, Map<String, String> headers,
                         long deadline, int maxBytes, BooleanSupplier cancelled) {
                NetClient.uri(uri.toString(), true, hosts);
                String body = "<title>Harness fixture</title><p>Fixture page evidence 中文来源</p>"
                        + "<script>SECRET_SCRIPT</script>";
                return new Response(200, Collections.singletonMap("content-type", "text/html; charset=utf-8"),
                        body.getBytes(StandardCharsets.UTF_8));
            }
        });
    }
}
