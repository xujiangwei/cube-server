/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.execution.QueryProvider;
import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 技能提供者的公共契约边界。
 *
 * <p>输入与输出 schema 只有一处来源：{@code harness-tools/} 下的技能定义，
 * 构建时嵌入产物。子类只需实现 {@link #execute}，参数校验、结果大小限制
 * 与可信上下文检查都在这里统一完成。</p>
 */
public abstract class AbstractProvider implements QueryProvider {
    /**
     * 单次结果字节数的默认上限：12 KiB。
     */
    private static final int DEFAULT_RESULT_BYTES = 12288;

    /**
     * 单次结果字节数的最小允许值：1 KiB。
     */
    private static final int MIN_RESULT_BYTES = 1024;

    /**
     * 单次结果字节数的最大允许值：12 KiB，与默认值一致，不允许配置得更大。
     */
    private static final int MAX_RESULT_BYTES = 12288;

    /**
     * SDK 包装后结果字节数的硬上限，超过即判定结果溢出。
     */
    private static final int RUNNER_RESULT_LIMIT = 32768;

    /**
     * 读取 schema 文件时的字节上限：64 KiB。
     */
    private static final int MAX_SCHEMA_BYTES = 65536;

    /**
     * 技能在 Broker 中的操作名。
     */
    private final String tool;

    /**
     * 调用该技能所需的权限名。
     */
    private final String permission;

    /**
     * 技能的输入 schema。
     */
    private final JSONObject input;

    /**
     * 技能的输出 schema。
     */
    private final JSONObject output;

    /**
     * 单次结果的字节数上限，来自配置项 {@code result.max.bytes} 。
     */
    protected final int resultBytes;

    /**
     * 构造函数：加载技能的输入/输出 schema，并读取结果大小上限。
     *
     * @param skill      技能名，对应 {@code harness-tools/} 下的目录。
     * @param tool       技能在 Broker 中的操作名。
     * @param permission 调用该技能所需的权限名。
     * @param config     技能私有配置。
     */
    protected AbstractProvider(String skill, String tool, String permission, Properties config) {
        this.tool = tool;
        this.permission = permission;
        this.input = schema(skill, "input");
        this.output = schema(skill, "output");
        this.resultBytes = number(config, "result.max.bytes", DEFAULT_RESULT_BYTES, MIN_RESULT_BYTES, MAX_RESULT_BYTES);
    }

    /**
     * 从产物内读取指定技能的 schema 文件并校验其结构。
     *
     * @param skill 技能名。
     * @param kind  schema 种类，取值 {@code input} 或 {@code output} 。
     * @return 返回解析后的 schema 对象。
     */
    protected static JSONObject schema(String skill, String kind) {
        String path = "/cube/service/harness/skill/schemas/" + skill + "/" + kind + ".schema.json";
        try (InputStream stream = AbstractProvider.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("SKILL_SCHEMA_MISSING: " + path);
            }
            JSONObject value = StrictJson.object(RuntimeSupport.read(stream, MAX_SCHEMA_BYTES));
            RuntimeSupport.toolSchema(value);
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("SKILL_SCHEMA_INVALID: " + path, e);
        }
    }

    /**
     * 返回技能的操作名，Broker 以它绑定工具与授权。
     *
     * @return 返回操作名，格式为 {@code <tool>.v1} 。
     */
    @Override
    public final String operation() {
        return this.tool + ".v1";
    }

    /**
     * 返回调用该技能所需的权限名。
     *
     * @return 返回权限名。
     */
    @Override
    public final String permission() {
        return this.permission;
    }

    /**
     * 返回输入 schema 的副本，调用方无法改动内部状态。
     *
     * @return 返回输入 schema。
     */
    @Override
    public final JSONObject inputSchema() {
        return new JSONObject(this.input.toString());
    }

    /**
     * 返回输出 schema 的副本，调用方无法改动内部状态。
     *
     * @return 返回输出 schema。
     */
    @Override
    public final JSONObject resultSchema() {
        return new JSONObject(this.output.toString());
    }

    /**
     * 按输入 schema 校验调用参数，子类可追加更严格的校验。
     *
     * @param args 调用参数。
     */
    @Override
    public void validate(JSONObject args) {
        Contract.validate(args, this.input);
    }

    /**
     * 不带可信上下文的查询入口一律拒绝，技能只能由 Broker 调用。
     *
     * @param args 调用参数。
     * @return 不会正常返回。
     */
    @Override
    public final JSONObject query(JSONObject args) {
        throw new StoreException("TRUSTED_CONTEXT_REQUIRED");
    }

    /**
     * 带可信上下文的查询入口：先检查上下文，再校验参数、执行并校验结果。
     *
     * @param context Broker 提供的可信调用上下文。
     * @param args    调用参数。
     * @return 返回技能执行结果。
     * @throws Exception 技能执行过程中的异常。
     */
    @Override
    public final JSONObject query(ProviderInvocation context, JSONObject args) throws Exception {
        check(context);
        validate(args);
        JSONObject result = execute(context, args);
        check(context);
        validateResult(args, result);
        return result;
    }

    /**
     * 执行技能的具体逻辑，由子类实现。
     *
     * @param context Broker 提供的可信调用上下文。
     * @param args    调用参数。
     * @return 返回技能执行结果。
     * @throws Exception 技能执行过程中的异常。
     */
    protected abstract JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception;

    /**
     * 校验结果是否符合输出 schema，并确认结果大小没有越界。
     *
     * @param args   调用参数。
     * @param result 技能返回的结果。
     */
    @Override
    public final void validateResult(JSONObject args, JSONObject result) {
        Contract.validate(result, this.output);
        if (bytes(result) > this.resultBytes || Budget.runnerBytes(result) >= RUNNER_RESULT_LIMIT) {
            throw new StoreException("TOOL_RESULT_LIMIT");
        }
    }

    /**
     * 检查调用上下文是否存在且未被取消。
     *
     * @param context Broker 提供的可信调用上下文。
     */
    protected static void check(ProviderInvocation context) {
        if (context == null) {
            throw new StoreException("TRUSTED_CONTEXT_REQUIRED");
        }
        if (context.cancelled()) {
            throw new StoreException("TOOL_CANCELLED");
        }
    }

    /**
     * 读取一个整数配置项，越界时直接拒绝启动。
     *
     * @param p        配置来源。
     * @param key      配置项名称。
     * @param fallback 配置缺失时的默认值。
     * @param min      允许的最小值。
     * @param max      允许的最大值。
     * @return 返回配置值。
     */
    public static int number(Properties p, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
        if (value < min || value > max) {
            throw new IllegalArgumentException("INVALID_PROVIDER_LIMIT: " + key);
        }
        return value;
    }

    /**
     * 计算对象序列化后的 UTF-8 字节数。
     *
     * @param value 待计算的对象。
     * @return 返回字节数。
     */
    static int bytes(JSONObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 释放技能持有的资源，默认没有需要释放的资源。
     */
    @Override
    public void close() {
    }
}
