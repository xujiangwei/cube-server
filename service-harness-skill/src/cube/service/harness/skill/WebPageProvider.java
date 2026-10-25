package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Public HTTPS static document reader, with per-hop DNS validation and IP-pinned TLS. */
public class WebPageProvider extends AbstractProvider {
    private final Set<String> hosts = new HashSet<>();
    private final NetClient http;
    private final int timeout, maxBytes;
    public WebPageProvider(Properties p) { this(p, null); }
    WebPageProvider(Properties p, NetClient client) {
        super("web", "common.web.read", "web.read", p);
        timeout = number(p, "network.timeout.millis", 5000, 100, 8000);
        maxBytes = number(p, "web.max.bytes", 1048576, 1024, 1048576);
        for (String host : p.getProperty("web.hosts", "").split(",")) {
            String value = host.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                if (!value.matches("[a-z0-9]+([.-][a-z0-9]+)*")) throw new IllegalArgumentException("WEB_HOST_INVALID");
                hosts.add(value);
            }
        }
        http = client == null ? new NetClient() : client;
    }
    public void validate(JSONObject args) {
        super.validate(args);
        NetClient.uri(args.getString("url"), true, hosts);
    }
    protected JSONObject execute(ProviderInvocation c, JSONObject args) { return read(args, c::cancelled); }
    JSONObject read(JSONObject args, BooleanSupplier cancelled) {
        validate(args);
        URI current = NetClient.uri(args.getString("url"), true, hosts);
        long deadline = NetClient.deadline(timeout);
        NetClient.Response response;
        int redirects = 0;
        Set<String> visited = new HashSet<>();
        while (true) {
            if (!visited.add(current.toString())) throw new StoreException("WEB_REDIRECT_LOOP");
            response = http.get(current, true, hosts,
                    Collections.singletonMap("Accept", "text/html, text/plain, application/json"),
                    deadline, maxBytes, cancelled);
            if (Arrays.asList(301, 302, 303, 307, 308).contains(response.status)) {
                if (++redirects > 3) throw new StoreException("WEB_REDIRECT_LIMIT");
                String location = response.header("location");
                if (location.isEmpty()) throw new StoreException("WEB_REDIRECT_INVALID");
                try { current = NetClient.uri(current.resolve(location).toString(), true, hosts); }
                catch (IllegalArgumentException e) { throw new StoreException("WEB_REDIRECT_INVALID"); }
            } else break;
        }
        if (response.status != 200) throw new StoreException(response.status == 429 ? "WEB_RATE_LIMIT" : "WEB_UPSTREAM_FAILED");
        String media = response.media(), text, title = "";
        if (!Arrays.asList("text/html", "text/plain", "application/json").contains(media))
            throw new StoreException("WEB_MEDIA_UNSUPPORTED");
        text = response.text();
        if (media.equals("text/html")) { HtmlText parsed = HtmlText.parse(text); title = parsed.title; text = parsed.text; }
        JSONObject out = new JSONObject().put("source", args.getString("url")).put("finalUrl", current.toASCIIString())
                .put("fetchedAt", Instant.now().toString()).put("mediaType", media)
                .put("title", Budget.prefix(title, 512)).put("sha256", RuntimeSupport.sha(response.body))
                .put("trust", "untrusted-source-data").put("text", Budget.prefix(text, 16384))
                .put("truncated", text.length() > 16384);
        Budget.text(out, "text", resultBytes);
        return out;
    }
    public void close() { http.close(); }
}
