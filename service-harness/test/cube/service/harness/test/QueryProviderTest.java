package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;

import cube.service.harness.execution.*;
import cube.service.harness.protocol.Contract;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/**
 * 通用查询提供方集成测试：以 weather SKILL 证明工具注册表不含 Console
 * 专属派发或权限，自定义 QueryProvider 经私有配置注册、schema 绑定与授权范围
 * 校验后参与通用调用；底层为一次性 sqlite 存储库。
 *
 * <p>验证点：未注册 provider 时入队返回 TOOL_PROVIDER_UNAVAILABLE；provider 私有
 * 配置不出现在 runner 可见的 wire 中；参数 schema 与注册不一致返回
 * TOOL_REGISTRY_SCHEMA_MISMATCH；身份权限交集固定 weather.read 并丢弃 unused.read；
 * 越范围目标返回 BROKER_SCOPE_FORBIDDEN；查询结果持久，重放不重复执行 provider；
 * 历史 manifest schema 返回 UNKNOWN_PACKAGE_SCHEMA。
 */
public final class QueryProviderTest {
    /**
     * 天气查询夹具 provider：只接受 targets 恰为 Beijing 的只读查询，返回固定预报。
     *
     * <p>由部署端私有配置实例化（private-test-key=provider-only）；queries 计数用于
     * 验证持久结果重放不会第二次执行 provider。
     */
    public static final class Weather implements QueryProvider {
        /** provider 实际执行次数；持久重放必须保持为 1。 */
        static AtomicInteger queries = new AtomicInteger();

        /**
         * 以部署端私有配置构造；私有键缺失或值错误直接失败。
         *
         * @param p provider 私有配置，必须包含 private-test-key=provider-only
         */
        public Weather(Properties p) {
            check(
                    p.getProperty("private-test-key").equals("provider-only"),
                    "private provider config missing");
        }

        /**
         * @return broker 路由使用的 operation 标识 example.weather.query.v1
         */
        public String operation() {
            return "example.weather.query.v1";
        }

        /**
         * @return 该 provider 要求的业务权限 weather.read
         */
        public String permission() {
            return "weather.read";
        }

        /**
         * @return 在标准参数 schema 基础上把 fields 枚举收紧为仅 tomorrow 的输入 schema
         */
        public JSONObject inputSchema() {
            JSONObject s = ToolBroker.parameters();
            s.getJSONObject("properties")
                    .getJSONObject("fields")
                    .getJSONObject("items")
                    .put("enum", new JSONArray().put("tomorrow"));
            return s;
        }

        /**
         * @return 要求 forecast 字符串（最大 128 字符）且禁止额外属性的输出 schema
         */
        public JSONObject resultSchema() {
            return new JSONObject()
                    .put("type", "object")
                    .put(
                            "properties",
                            new JSONObject()
                                    .put("forecast", new JSONObject().put("type", "string").put("maxLength", 128)))
                    .put("required", new JSONArray().put("forecast"))
                    .put("additionalProperties", false);
        }

        /**
         * 校验输入 schema 与授权范围：仅允许 targets 恰为 Beijing，否则拒绝。
         *
         * @param a 查询参数
         */
        public void validate(JSONObject a) {
            Contract.validate(a, inputSchema());
            if (!RuntimeSupport.strings(a.getJSONArray("targets"))
                    .equals(Collections.singleton("Beijing"))) {
                throw new StoreException("BROKER_SCOPE_FORBIDDEN");
            }
        }

        /**
         * 执行查询：先做 schema 与范围校验，再递增计数并返回固定预报。
         *
         * @param a 查询参数
         * @return 含 forecast=fixture-only 的结果 JSON
         */
        public JSONObject query(JSONObject a) {
            validate(a);
            queries.incrementAndGet();
            return new JSONObject().put("forecast", "fixture-only");
        }

        /**
         * 按输出 schema 校验 provider 结果。
         *
         * @param a 原始查询参数
         * @param result provider 返回的结果
         */
        public void validateResult(JSONObject a, JSONObject result) {
            Contract.validate(result, resultSchema());
        }

        /** 无可释放资源。 */
        public void close() {}
    }

    /**
     * 测试入口：发布 weather SKILL 与 TaskProfile，经 ToolBroker 完成 provider
     * 注册、授权、schema 绑定与持久查询重放校验。
     *
     * @param ignored 未使用的命令行参数
     * @throws Exception 发布、上传、broker 或断言失败
     */
    public static void main(String[] ignored) throws Exception {
        ToolTest.prepare(Files.createTempDirectory("harness-query-provider-"), "");
        // 私有配置经匿名 Properties 传入，模拟部署端单独分发的 provider 配置。
        Weather weather =
                new Weather(
                        new Properties() {
                            {
                                setProperty("private-test-key", "provider-only");
                            }
                        });
        String name = "example.weather.query";
        Map<String, byte[]> e =
                entries("Activate the weather SKILL before querying Beijing tomorrow. Fixture only.");
        e.put("input.schema.json", RuntimeSupport.bytes(weather.inputSchema()));
        e.put("output.schema.json", RuntimeSupport.bytes(weather.resultSchema()));
        e.put("main.py", bytes("from runner_sdk import context,query,result\nresult(query(**context()['arguments']))\n"));
        JSONObject m =
                manifest(e)
                        .put("schemaVersion", 6)
                        .put("name", "example/weather")
                        .put("runtime", ref("cpu"))
                        .put(
                                "permissions",
                                new JSONArray()
                                        .put("skill.list")
                                        .put("skill.activate")
                                        .put("resource.list")
                                        .put("resource.read")
                                        .put("resource.search")
                                        .put("weather.read"));
        // main.py 固定为 entrypoint。
        for (int i = 0; i < m.getJSONArray("files").length(); i++) {
            JSONObject f = m.getJSONArray("files").getJSONObject(i);
            if (f.getString("path").equals("main.py")) {
                f.put("role", "entrypoint");
            }
        }
        // 工具经 brokerOperation 绑定到自定义 provider，而非 Console 内建入口。
        m.getJSONArray("tools")
                .put(
                        new JSONObject()
                                .put("name", name)
                                .put("kind", "executable")
                                .put("entrypoint", "main.py")
                                .put("inputSchema", "input.schema.json")
                                .put("outputSchema", "output.schema.json")
                                .put("permission", "weather.read")
                                .put("effect", "read-only")
                                .put("brokerOperation", weather.operation()).put("runnerType", "tool"));
        String digest = upload(zip(m, e)).getString("digest");
        catalog.lifecycle(T, digest, 1, "ready");
        // 身份权限 = 平台权限 + weather.read + unused.read；固定后只保留被引用者的交集。
        Set<String> scopes = new HashSet<>(TEST_PERMISSIONS);
        scopes.add("weather.read");
        scopes.add("unused.read");
        JSONObject task =
                task("1", digest, "scripted", 4096)
                        .put("id", "weather")
                        .put("schemaVersion", 2)
                        .put("runtime", ref("cpu"))
                        .put("permissions", new JSONArray(scopes))
                        .put("toolSetVersion", "weather-tools-v1")
                        .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"))
                        .put(
                                "executionLimits",
                                new JSONObject()
                                        .put("maxRounds", 8)
                                        .put("maxTools", 16)
                                        .put("maxRepairs", 1)
                                        .put("maxLifetimeMillis", 60000));
        profiles.publish(T, task);
        catalog.bind(T, "task", "weather", "weather@1", 0);
        ProfileRegistry.Identity actor = new ProfileRegistry.Identity(T, S, scopes);
        String run =
                profiles
                        .createRun(
                                actor,
                                key(),
                                "weather",
                                request("Beijing tomorrow").put("taskProfileId", "weather"))
                        .getString("runId");
        // 固定权限交集：weather.read 保留，未使用的 unused.read 被丢弃。
        Set<String> pinned =
                RuntimeSupport.strings(profiles.pinned(actor, run).getJSONArray("permissions"));
        check(
                pinned.contains("weather.read") && !pinned.contains("unused.read"),
                "extension permission intersection");
        resources.activate(actor, run, digest);
        JdbcRunStore.Lease lease = running(run, 60000);
        JSONObject a =
                new JSONObject()
                        .put("targets", new JSONArray().put("Beijing"))
                        .put("fields", new JSONArray().put("tomorrow"));
        intent(
                lease,
                "call",
                "call",
                new JSONObject()
                        .put("proposalId", "call")
                        .put("tool", name)
                        .put("argumentsJson", JdbcRunStore.canonical(a)));
        JSONObject intent = row(run, "call", "call");
        JSONObject definition = resources.executable(actor, run, name);
        ToolJobStore jobs = new ToolJobStore(store, config.mysql());
        Properties p = new Properties();
        p.setProperty("runner.grant.key", key() + key());
        p.setProperty("broker.node.id", "generic-registry");
        // 未注册任何 provider：入队必须返回 TOOL_PROVIDER_UNAVAILABLE。
        try (ToolBroker empty = new ToolBroker(jobs, actor, p)) {
            fail("TOOL_PROVIDER_UNAVAILABLE", () -> empty.enqueue(lease, intent, definition));
        }
        // 注册 weather provider：类名加私有配置文件路径，配置内容不进入 wire。
        Path privateConfig = root.resolve("weather.local.properties");
        Files.write(privateConfig, bytes("private-test-key=provider-only\n"));
        p.setProperty("broker.providers", "weather");
        p.setProperty("broker.provider.weather.class", Weather.class.getName());
        p.setProperty("broker.provider.weather.config", privateConfig.toAbsolutePath().toString());
        try (ToolBroker broker = new ToolBroker(jobs, actor, p)) {
            // 定义参数 schema 与注册表不一致：TOOL_REGISTRY_SCHEMA_MISMATCH。
            fail(
                    "TOOL_REGISTRY_SCHEMA_MISMATCH",
                    () ->
                            broker.enqueue(
                                    lease,
                                    intent,
                                    RuntimeSupport.copy(definition).put("parameters", ToolBroker.parameters())));
            // 身份缺少 weather.read：TOOL_PROFILE_FORBIDDEN。
            try (ToolBroker denied = new ToolBroker(jobs, identity, p)) {
                fail("TOOL_PROFILE_FORBIDDEN", () -> denied.enqueue(lease, intent, definition));
            }
            JSONObject job = broker.enqueue(lease, intent, definition);
            jobs.identity("weather-runner", "weather-runtime", 1);
            JSONObject wire =
                    jobs.poll("weather-runner", "weather-runtime", "instance", true, broker::wire);
            // 私有配置不得出现在 runner 可见的 wire 中。
            check(!wire.toString().contains("provider-only"), "private provider config leaked");
            jobs.accepted(job.getString("job_id"), wire.getString("jobHash"));
            job = jobs.job(job.getString("job_id"));
            JSONObject out = null;
            long end = System.currentTimeMillis() + 5000;
            // 轮询 broker 直至查询 settled。
            do {
                out =
                        broker.query(
                                job,
                                wire.getJSONObject("grant"),
                                a.getJSONArray("targets"),
                                a.getJSONArray("fields"));
                if (out.getString("status").equals("finished")) {
                    break;
                }
                Thread.sleep(20);
            } while (System.currentTimeMillis() < end);
            // 通用 query 派发：provider 返回的 forecast 必须原样可见。
            check(
                    out.getString("status").equals("finished")
                            && new JSONObject(out.getString("dataJson"))
                                    .getString("forecast")
                                    .equals("fixture-only"),
                    "generic query dispatch");
            // 重放同一作业：provider 不得被第二次执行。
            broker.query(
                    job, wire.getJSONObject("grant"), a.getJSONArray("targets"), a.getJSONArray("fields"));
            check(Weather.queries.get() == 1, "provider replayed");
        }
        // 历史 manifest schema（3）不再接受：UNKNOWN_PACKAGE_SCHEMA。
        JSONObject legacy = RuntimeSupport.copy(m).put("schemaVersion", 3);
        fail(
                "UNKNOWN_PACKAGE_SCHEMA",
                () -> repo.validate(new java.io.ByteArrayInputStream(zip(legacy, e))));
        pass(
                "generic weather SKILL: optional provider, custom scope intersection, private configuration, schema pin, authorization and durable query replay");
    }
}
