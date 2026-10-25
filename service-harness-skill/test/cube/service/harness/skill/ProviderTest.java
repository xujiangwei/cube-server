package cube.service.harness.skill;

import com.sun.net.httpserver.*;
import cube.service.harness.execution.QueryProvider;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.*;
import cube.service.harness.store.StoreException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import javax.net.ssl.*;
import org.json.*;

/** Actual HTTP/TLS fixtures, production package validator, and provider behavior boundaries. */
public final class ProviderTest {
    private static int assertions;
    interface Action { void run() throws Exception; }
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static void fails(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (RuntimeException e) { check(code.equals(e.getMessage()), code + " != " + e.getMessage()); }
    }
    static Properties properties(String... pairs) {
        Properties p = new Properties();
        for (int i = 0; i < pairs.length; i += 2) p.setProperty(pairs[i], pairs[i + 1]);
        return p;
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static NetClient.Response response(int code, String type, String body) {
        return new NetClient.Response(code, Collections.singletonMap("content-type", type), bytes(body));
    }
    static void send(HttpExchange request, int code, String media, byte[] body) throws IOException {
        request.getResponseHeaders().set("Content-Type", media);
        request.sendResponseHeaders(code, body.length);
        try (OutputStream stream = request.getResponseBody()) { stream.write(body); }
    }
    private static final BooleanSupplier ACTIVE = () -> false;

    public static void main(String[] args) throws Exception {
        Path packages = Paths.get(args[0]);
        Path root = Files.createTempDirectory(packages.getParent(), "provider-tests-");
        CalculatorProvider calc = new CalculatorProvider(new Properties());
        check(calc.calculate(new JSONObject().put("expression", "-(1.2 + 2.3) * 4 + 17 % 5")).getString("result").equals("-12"), "precedence/unary/modulo");
        check(calc.calculate(new JSONObject().put("expression", "0.1+0.2")).getString("result").equals("0.3"), "decimal precision");
        check(calc.calculate(new JSONObject().put("expression", "1/3")).getString("result").equals("0.3333333333333333333333333333333333"), "DECIMAL128 rounding");
        for (String expression : Arrays.asList("1/0", "3%0")) fails("CALCULATION_DIVIDE_BY_ZERO", () -> calc.calculate(new JSONObject().put("expression", expression)));
        for (String expression : Arrays.asList("", "2**3", "1e3", "Math.random()", "1 2", "1..2"))
            fails(expression.isEmpty() ? "SCHEMA_INVALID" : "CALCULATION_EXPRESSION_INVALID", () -> calc.calculate(new JSONObject().put("expression", expression)));
        fails("CALCULATION_LIMIT", () -> calc.calculate(new JSONObject().put("expression", new String(new char[40]).replace('\0', '-') + "1")));
        fails("TRUSTED_CONTEXT_REQUIRED", () -> calc.query(new JSONObject().put("expression", "1")));

        TimeProvider time = new TimeProvider(new Properties(), Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC));
        check(time.value(new JSONObject().put("action", "now")).getString("localTime").equals("2026-10-25T08:00:00+08:00"), "default Shanghai time");
        for (String[] sample : new String[][]{{"2026-03-08T06:30:00Z", "-05:00"}, {"2026-03-08T07:30:00Z", "-04:00"}})
            check(time.value(new JSONObject().put("action", "convert").put("instant", sample[0]).put("zone", "America/New_York")).getString("offset").equals(sample[1]), "DST transition");
        fails("TIME_ARGUMENTS_INVALID", () -> time.value(new JSONObject().put("action", "convert")));
        fails("TIME_ARGUMENTS_INVALID", () -> time.value(new JSONObject().put("action", "convert").put("instant", "2026-10-25T12:00:00")));
        fails("TIME_ARGUMENTS_INVALID", () -> time.value(new JSONObject().put("action", "now").put("instant", "2026-10-25T00:00:00Z")));

        HtmlText html = HtmlText.parse("<html><head><title>Cube &amp; 工具</title><style>SECRET_STYLE</style><script>SECRET_SCRIPT</script></head><body><h1>你好</h1><p>text &amp; content</p></body></html>");
        check(html.title.equals("Cube & 工具") && html.text.contains("你好") && !html.text.contains("SECRET"), "static HTML extraction and entity decoding");
        TextAttachmentProvider attachment = new TextAttachmentProvider(new Properties());
        String large = new String(new char[3000]).replace("\0", "中😀\\\"\n");
        byte[] content = bytes(Budget.prefix(large, 6000));
        JSONObject attached = attachment.content("input-a", content);
        attachment.validateResult(new JSONObject(), attached);
        check(attached.getBoolean("truncated") && AbstractProvider.bytes(attached) <= 12288 && Budget.runnerBytes(attached) < 32768, "UTF-8 and nested JSON budgets");
        check(attached.getString("sha256").equals(RuntimeSupport.sha(content)), "digest remains full-input digest");
        fails("ATTACHMENT_TEXT_INVALID", () -> attachment.content("x", new byte[]{(byte) 0xff}));
        fails("ATTACHMENT_FORMAT_FORBIDDEN", () -> attachment.content("x", bytes("%PDF-x")));

        Path index = root.resolve("index.json");
        JSONArray docs = new JSONArray();
        for (int i = 0; i < 3; i++) docs.put(new JSONObject().put("id", "doc" + i).put("tenant", i == 2 ? "foreign" : "tenant")
                .put("subjects", new JSONArray().put(i == 1 ? "other" : "user"))
                .put("source", "kb:" + i).put("text", i == 0 ? "Harness public evidence" : "Harness SECRET"));
        byte[] raw = bytes(new JSONObject().put("documents", docs).toString());
        Files.write(index, raw);
        Properties kp = properties("knowledge.index", index.toString(), "knowledge.index.sha256", RuntimeSupport.sha(raw));
        KnowledgeProvider knowledge = new KnowledgeProvider(kp);
        JSONObject found = knowledge.lookup("tenant", "user", new JSONObject().put("query", "hARNESS"), ACTIVE);
        check(found.getJSONArray("citations").length() == 1 && !found.toString().contains("SECRET"), "ACL before matching");
        check(knowledge.lookup("tenant", "nobody", new JSONObject().put("query", "Harness"), ACTIVE).getJSONArray("citations").length() == 0, "no ACL count leak");
        Properties bad = properties("knowledge.index", index.toString(), "knowledge.index.sha256", "bad");
        try { new KnowledgeProvider(bad); throw new AssertionError("digest mismatch accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }

        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        server.setExecutor(executor);
        server.createContext("/search", request -> {
            requests.incrementAndGet();
            String q = URLDecoder.decode(request.getRequestURI().getRawQuery(), "UTF-8");
            if (q.contains("forbidden")) { send(request, 403, "application/json", bytes("{}")); return; }
            if (q.contains("limited")) { send(request, 429, "application/json", bytes("{}")); return; }
            if (q.contains("invalid")) { send(request, 200, "application/json", bytes("{bad}")); return; }
            if (q.contains("slow")) { try { Thread.sleep(300); } catch (InterruptedException ignored) {} }
            JSONArray list = new JSONArray();
            if (!q.contains("empty")) list.put(new JSONObject().put("title", "Cube &amp; Tools").put("url", "https://example.com/tools")
                    .put("content", "<p>3D <b>建模</b></p>").put("engine", "fixture"));
            send(request, 200, "application/json; charset=utf-8", bytes(new JSONObject().put("results", list)
                    .put("unresponsive_engines", q.contains("partial") ? new JSONArray().put(new JSONArray().put("fixture-b").put("timeout")) : new JSONArray()).toString()));
        });
        server.start();
        Properties sp = properties("search.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        try (SearchProvider search = new SearchProvider(sp)) {
            JSONObject result = search.search(new JSONObject().put("query", "主流3D建模软件 partial"), ACTIVE);
            search.validateResult(new JSONObject(), result);
            check(result.getBoolean("partial") && result.getJSONArray("results").getJSONObject(0).getString("snippet").equals("3D 建模"), "real SearXNG HTTP/Chinese/partial");
            check(search.search(new JSONObject().put("query", "empty"), ACTIVE).getJSONArray("results").length() == 0, "empty search");
            fails("SEARCH_FORBIDDEN", () -> search.search(new JSONObject().put("query", "forbidden"), ACTIVE));
            fails("SEARCH_RATE_LIMIT", () -> search.search(new JSONObject().put("query", "limited"), ACTIVE));
            fails("SEARCH_RESPONSE_INVALID", () -> search.search(new JSONObject().put("query", "invalid"), ACTIVE));
            int before = requests.get();
            fails("TOOL_CANCELLED", () -> search.search(new JSONObject().put("query", "cancelled"), () -> true));
            check(requests.get() == before, "cancelled call never reaches upstream");
            Properties shortTimeout = properties("search.url", sp.getProperty("search.url"), "network.timeout.millis", "100");
            try (SearchProvider impatient = new SearchProvider(shortTimeout)) {
                fails("WEB_TIMEOUT", () -> impatient.search(new JSONObject().put("query", "slow"), ACTIVE));
            }
        } finally { server.stop(0); executor.shutdownNow(); }

        for (String ip : Arrays.asList("0.0.0.0", "10.1.2.3", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.0.1", "100.64.0.1", "224.0.0.1", "::1", "2001:db8::1"))
            check(!NetClient.publicAddress(InetAddress.getByName(ip)), "special address " + ip);
        check(NetClient.publicAddress(InetAddress.getByName("8.8.8.8")), "public IPv4");
        AtomicInteger dnsCalls = new AtomicInteger();
        try (NetClient net = new NetClient(host -> {
            dnsCalls.incrementAndGet(); return new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")};
        }, SSLContext.getDefault())) {
            fails("WEB_DNS_FORBIDDEN", () -> net.get(URI.create("https://fixture.invalid/"), true, Collections.emptySet(), Collections.emptyMap(), NetClient.deadline(500), 1024, ACTIVE));
            check(dnsCalls.get() == 1, "all DNS addresses checked in one resolution");
        }
        try (WebPageProvider page = new WebPageProvider(new Properties())) {
            fails("WEB_DNS_FORBIDDEN", () -> page.read(new JSONObject().put("url", "https://127.0.0.1/"), ACTIVE));
            fails("WEB_URL_FORBIDDEN", () -> page.read(new JSONObject().put("url", "http://example.com/"), ACTIVE));
            fails("WEB_URL_FORBIDDEN", () -> page.read(new JSONObject().put("url", "https://[::ffff:8.8.8.8]/"), ACTIVE));
        }
        try (WebPageProvider page = new WebPageProvider(new Properties(), new NetClient() {
            Response get(URI uri, boolean pub, Set<String> hosts, Map<String, String> headers, long deadline, int max, BooleanSupplier cancelled) {
                if (uri.getPath().equals("/redirect")) return new Response(302, Collections.singletonMap("location", "/document"), new byte[0]);
                if (uri.getPath().equals("/downgrade")) return new Response(302, Collections.singletonMap("location", "http://fixture.invalid/document"), new byte[0]);
                return response(200, "text/html", "<title>Fixture</title><p>静态正文</p><script>SECRET</script>");
            }
        })) {
            JSONObject value = page.read(new JSONObject().put("url", "https://fixture.invalid/redirect"), ACTIVE);
            check(value.getString("finalUrl").endsWith("/document") && value.getString("text").contains("静态正文") && !value.toString().contains("SECRET"), "redirect + actual HTML projection");
            fails("WEB_URL_FORBIDDEN", () -> page.read(new JSONObject().put("url", "https://fixture.invalid/downgrade"), ACTIVE));
        }

        tls(root);
        List<QueryProvider> providers = Arrays.asList(new SearchProvider(sp), new WebPageProvider(new Properties()), knowledge, attachment, time, calc);
        try {
            String[] names = {"search", "web", "knowledge", "attachment", "time", "calculator"};
            PackageRepository repository = new PackageRepository(null, null, Collections.emptyMap(), true, true);
            for (int i = 0; i < names.length; i++) {
                Path archive = packages.resolve(names[i] + "-v1.zip");
                try (InputStream stream = Files.newInputStream(archive); ZipFile zip = new ZipFile(archive.toFile())) {
                    PackageRepository.Validated value = repository.validate(stream);
                    check(value.manifest.getString("name").equals("common/" + names[i]), "production ZIP validation " + names[i]);
                    JSONObject tool = value.manifest.getJSONArray("tools").getJSONObject(0);
                    check(tool.getString("brokerOperation").equals(providers.get(i).operation()) && tool.getString("permission").equals(providers.get(i).permission()), "provider binding " + names[i]);
                    for (String kind : Arrays.asList("input", "output")) {
                        byte[] schema;
                        try (InputStream in = zip.getInputStream(zip.getEntry(kind + ".schema.json"))) { schema = RuntimeSupport.read(in, 65536); }
                        check(StrictJson.object(schema).similar(kind.equals("input") ? providers.get(i).inputSchema() : providers.get(i).resultSchema()), "schema identity " + names[i]);
                    }
                }
            }
        } finally { for (QueryProvider provider : providers) provider.close(); }
        System.out.println("PASS: " + assertions + " provider/package/HTTP/TLS assertions");
    }

    private static void tls(Path root) throws Exception {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path keytool = javaHome.resolve("bin/keytool" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
        Path store = root.resolve("fixture.jks");
        char[] password = "isolated-fixture-only".toCharArray();
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", store.toString(), "-storepass", new String(password), "-keypass", new String(password),
                "-dname", "CN=fixture.invalid", "-ext", "SAN=dns:fixture.invalid,ip:127.0.0.1", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).start();
        try (InputStream in = process.getInputStream()) { RuntimeSupport.read(in, 16384); }
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "create isolated TLS certificate");
        KeyStore keys = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(store)) { keys.load(in, password); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", request -> {
            if (request.getRequestURI().getPath().equals("/large")) send(request, 200, "text/plain", new byte[8192]);
            else {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (GZIPOutputStream out = new GZIPOutputStream(buffer)) { out.write(bytes("chunked gzip TLS 正文")); }
                request.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                request.getResponseHeaders().set("Content-Encoding", "gzip");
                request.sendResponseHeaders(200, 0);
                try (OutputStream out = request.getResponseBody()) { out.write(buffer.toByteArray()); }
            }
        });
        server.start();
        try (NetClient client = new NetClient(host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}, context)) {
            String url = "https://fixture.invalid:" + server.getAddress().getPort();
            check(client.get(URI.create(url + "/"), false, Collections.emptySet(), Collections.emptyMap(), NetClient.deadline(5000), 1024, ACTIVE).text().contains("TLS 正文"), "pinned IP + TLS/SNI + chunked gzip");
            fails("WEB_RESPONSE_LIMIT", () -> client.get(URI.create(url + "/large"), false, Collections.emptySet(), Collections.emptyMap(), NetClient.deadline(5000), 1024, ACTIVE));
            try {
                client.get(URI.create(url.replace("fixture.invalid", "wrong.invalid") + "/"), false, Collections.emptySet(), Collections.emptyMap(), NetClient.deadline(5000), 1024, ACTIVE);
                throw new AssertionError("TLS hostname mismatch accepted");
            } catch (StoreException expected) { check(expected.code.startsWith("WEB_"), "TLS hostname verification"); }
        } finally { server.stop(0); }
    }
}
