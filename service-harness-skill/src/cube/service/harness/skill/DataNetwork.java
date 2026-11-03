package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.store.StoreException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;

/** Access only an administrator-approved immutable source; never follow redirects. */
final class DataNetwork implements AutoCloseable {
    private final Properties config;
    private final NetClient http = new NetClient();
    DataNetwork(Properties config) { this.config = config; }
    NetClient.Response get(ProviderInvocation c, String ref, JSONObject request, JSONObject values, String scope, JSONObject extra, int limit) {
        JSONObject source = c.source(ref);
        String path = request.getString("path");
        boolean allowed = false;
        JSONArray paths = source.getJSONArray("allowedPaths");
        for (int i = 0; i < paths.length(); i++) if (paths.getString(i).equals(path)) allowed = true;
        if (!allowed) throw new StoreException("DATA_SOURCE_FORBIDDEN");
        TreeMap<String, String> query = new TreeMap<>();
        JSONObject template = request.getJSONObject("query");
        for (String key : template.keySet()) {
            String value = template.getString(key), prefix = "${" + scope + ".";
            if (value.startsWith(prefix)) value = ConnectorConfig.scalar(values.get(value.substring(prefix.length(), value.length() - 1)));
            query.put(key, value);
        }
        for (String key : extra.keySet()) { if (query.containsKey(key)) ConnectorConfig.bad(); query.put(key, extra.getString(key)); }
        StringBuilder target = new StringBuilder(path);
        for (Map.Entry<String,String> e : query.entrySet()) target.append(target.indexOf("?") < 0 ? '?' : '&').append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        String issued = Long.toString(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
        String secret = config.getProperty("secret." + source.getString("secretRef"), "");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new StoreException("DATA_SOURCE_FORBIDDEN");
        Map<String,String> headers = new HashMap<>();
        headers.put("X-Harness-Tenant", c.tenant); headers.put("X-Harness-Subject", c.subject);
        headers.put("X-Harness-Issued-At", issued); headers.put("X-Harness-Nonce", nonce);
        headers.put("X-Harness-Source", ref);
        String canonical = "GET\n" + target + "\n" + ref + "\n" + c.tenant + "\n" + c.subject + "\n" + issued + "\n" + nonce;
        headers.put("X-Harness-Signature", sign(secret, canonical));
        URI uri = NetClient.uri(source.getString("baseUrl") + target, false, Collections.emptySet());
        NetClient.Response response = http.get(uri, false, Collections.emptySet(), headers, NetClient.deadline(5000), limit, c::cancelled);
        if (response.status == 403 || response.status == 401) throw new StoreException("DATA_SOURCE_FORBIDDEN");
        if (response.status == 409 || response.status == 410) throw new StoreException("MEDIA_VERSION_CHANGED");
        if (response.status != 200) throw new StoreException("DATA_QUERY_INCOMPLETE");
        c.source(ref); // Revocation during the network request must also fail closed.
        return response;
    }
    static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8"); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    static String sign(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte b : mac.doFinal(text.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public void close() { http.close(); }
}
