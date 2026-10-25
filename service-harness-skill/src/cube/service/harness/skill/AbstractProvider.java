package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.execution.QueryProvider;
import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.json.JSONObject;

/** Shared contract boundary. A schema has one source, in harness-tools/, embedded at build time. */
public abstract class AbstractProvider implements QueryProvider {
    private final String tool, permission;
    private final JSONObject input, output;
    protected final int resultBytes;

    protected AbstractProvider(String skill, String tool, String permission, Properties config) {
        this.tool = tool;
        this.permission = permission;
        this.input = schema(skill, "input");
        this.output = schema(skill, "output");
        resultBytes = number(config, "result.max.bytes", 12288, 1024, 12288);
    }

    private static JSONObject schema(String skill, String kind) {
        String path = "/cube/service/harness/skill/schemas/" + skill + "/" + kind + ".schema.json";
        try (InputStream stream = AbstractProvider.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("SKILL_SCHEMA_MISSING: " + path);
            JSONObject value = StrictJson.object(RuntimeSupport.read(stream, 65536));
            RuntimeSupport.toolSchema(value);
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("SKILL_SCHEMA_INVALID: " + path, e);
        }
    }

    public final String operation() { return tool + ".v1"; }
    public final String permission() { return permission; }
    public final JSONObject inputSchema() { return new JSONObject(input.toString()); }
    public final JSONObject resultSchema() { return new JSONObject(output.toString()); }
    public void validate(JSONObject args) { Contract.validate(args, input); }
    public final JSONObject query(JSONObject args) { throw new StoreException("TRUSTED_CONTEXT_REQUIRED"); }

    public final JSONObject query(ProviderInvocation context, JSONObject args) throws Exception {
        check(context);
        validate(args);
        JSONObject result = execute(context, args);
        check(context);
        validateResult(args, result);
        return result;
    }

    protected abstract JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception;

    public final void validateResult(JSONObject args, JSONObject result) {
        Contract.validate(result, output);
        if (bytes(result) > resultBytes || Budget.runnerBytes(result) >= 32768)
            throw new StoreException("TOOL_RESULT_LIMIT");
    }

    protected static void check(ProviderInvocation context) {
        if (context == null) throw new StoreException("TRUSTED_CONTEXT_REQUIRED");
        if (context.cancelled()) throw new StoreException("TOOL_CANCELLED");
    }

    public static int number(Properties p, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("INVALID_PROVIDER_LIMIT: " + key);
        return value;
    }

    static int bytes(JSONObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    public void close() {}
}
