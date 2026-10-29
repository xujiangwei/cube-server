/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.usage;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * 用量字段解码器。
 *
 * <p>特点是“存在性感知”：字段缺失、为 {@code null} 、为小数或为负数时一律返回 {@code null} ，
 * 绝不把“没拿到”当成 0。这个区别决定了账目记为 {@code unknown} 还是 {@code actual}——
 * 补零会把不可信的应答伪装成真实计量。</p>
 *
 * <p>兼容多种提供方命名：输入取自 {@code prompt_tokens} / {@code inputTokens} / {@code tokens} /
 * {@code input} ，输出取自 {@code completion_tokens} / {@code outputTokens} / {@code generatedTokens} /
 * {@code output} 。载荷可能是完整响应，也可能是嵌在 {@code usage} 或 {@code performance} 里的用量对象。</p>
 */
public final class UsageTokens {

    private UsageTokens() {
    }

    /**
     * 从响应载荷中读取输入与输出 Token。
     *
     * <p>优先读 {@code usage} 子对象，其次 {@code performance} 子对象，两者都没有则把整个载荷当作用量对象。
     * 数值经 {@link BigDecimal#longValueExact()} 转换，因此小数被视为不可信，而不是四舍五入或截断。</p>
     *
     * @param payload 完整响应或用量对象。
     * @return 长度为 2 的数组，依次为输入与输出 Token ；无法可信取值时返回 {@code null} 。
     */
    public static long[] read(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        JSONObject usage = payload.optJSONObject("usage");
        if (usage == null) {
            usage = payload.optJSONObject("performance");
        }
        if (usage == null) {
            usage = payload;
        }
        String in = usage.has("prompt_tokens") ? "prompt_tokens" : usage.has("inputTokens") ? "inputTokens" : usage.has("tokens") ? "tokens" : "input";
        String out = usage.has("completion_tokens") ? "completion_tokens" : usage.has("outputTokens") ? "outputTokens" : usage.has("generatedTokens") ? "generatedTokens" : "output";
        try {
            if (!usage.has(in) || !usage.has(out) || usage.isNull(in) || usage.isNull(out)) {
                return null;
            }
            long a = new BigDecimal(usage.get(in).toString()).longValueExact();
            long b = new BigDecimal(usage.get(out).toString()).longValueExact();
            return a < 0 || b < 0 ? null : new long[]{a, b};
        } catch (RuntimeException e) {
            // 非数字、小数或超出 long 范围，一律按不可信用量处理。
            return null;
        }
    }
}
