/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.usage;

import cube.auth.AuthToken;

/**
 * AI 用量的结算身份。
 *
 * <p>结算主体由「域 + Contact ID」确定，模块、请求与 run 只是归因用的附加维度。
 * 组合身份是显式构造出来的，<b>不允许从 REST 请求体反序列化</b>——否则调用方可以伪造归属，
 * 把消费计到别人的账上。</p>
 *
 * <p>实例不可变。{@link #delegate()} 生成的副本带有 {@code delegated} 标记，表示身份只用于
 * 透传给下游执行单元，本端不记账（由发起方如 Harness 统一记账）。</p>
 *
 * @see UsageLedger#begin(UsageContext, String, String, String, long)
 */
public final class UsageContext {

    /**
     * 结算域。无法确定归属的进程内任务使用 {@code system} 。
     */
    public final String domain;

    /**
     * 模块名，用于按模块归因，例如 Harness、知识问答。
     */
    public final String module;

    /**
     * 关联的请求 ID，无关联时为空串。
     */
    public final String requestId;

    /**
     * 关联的 run ID，无关联时为空串。
     */
    public final String runId;

    /**
     * 结算 Contact ID。系统任务为 0，Console 账单会排除这些记录。
     */
    public final long contactId;

    /**
     * 是否为委托身份。为 {@code true} 时本端不记账，仅把身份透传给下游。
     */
    public final boolean delegated;

    /**
     * 构造结算身份。
     *
     * @param domain    结算域，不能为空且不超过 128 个字符。
     * @param contactId 结算 Contact ID，不能为负数。
     * @param module    模块名，允许为 {@code null} 。
     * @param requestId 关联请求 ID，{@code null} 归一为空串。
     * @param runId     关联 run ID，{@code null} 归一为空串。
     * @param delegated 是否为委托身份。
     * @throws IllegalArgumentException 域非法或 Contact ID 为负数。
     */
    private UsageContext(String domain, long contactId, String module, String requestId, String runId, boolean delegated) {
        if (domain == null || domain.isEmpty() || domain.length() > 128 || contactId < 0) {
            throw new IllegalArgumentException("INVALID_USAGE_IDENTITY");
        }
        this.domain = domain;
        this.contactId = contactId;
        this.module = module;
        this.requestId = requestId == null ? "" : requestId;
        this.runId = runId == null ? "" : runId;
        this.delegated = delegated;
    }

    /**
     * 由已验证的访问令牌构造结算身份。
     *
     * <p>令牌必须已绑定 Contact 且在有效期内，否则拒绝——没有原始访问者的请求不能冒用他人归属。</p>
     *
     * @param token     请求方持有的 AuthToken，允许为 {@code null} 。
     * @param module    模块名。
     * @param requestId 关联请求 ID。
     * @return 结算身份，{@code delegated} 为 {@code false} 。
     * @throws IllegalArgumentException 令牌为空、未绑定 Contact 或已过期。
     */
    public static UsageContext visitor(AuthToken token, String module, String requestId) {
        long contact = 0;
        try {
            if (token != null) {
                contact = token.getContactId();
            }
        } catch (NullPointerException unboundToken) {
            // 早期版本的令牌可能没有绑定 Contact，此时保持 0 并走下方校验。
        }
        if (token == null || contact <= 0 || token.getExpiry() <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("CONTACT_REQUIRED_FOR_AI_USAGE");
        }
        return new UsageContext(token.getDomain(), contact, module, requestId, "", false);
    }

    /**
     * 由 Harness 的 run 归属构造结算身份。
     *
     * <p>模块固定为 {@code Harness} ，域与 Contact 取自 run 的 tenant / subject_id 。
     * 推理 ID 作为请求 ID 记录，便于把账目回溯到具体推理。</p>
     *
     * @param domain      结算域，即 run 的 tenant 。
     * @param contactId   结算 Contact ID，即 run 的 subject_id 。
     * @param runId       关联 run ID。
     * @param inferenceId 关联推理 ID。
     * @return 结算身份，{@code delegated} 为 {@code false} 。
     * @throws IllegalArgumentException Contact ID 不大于 0 。
     */
    public static UsageContext run(String domain, long contactId, String runId, String inferenceId) {
        if (contactId <= 0) {
            throw new IllegalArgumentException("CONTACT_REQUIRED_FOR_AI_USAGE");
        }
        return new UsageContext(domain, contactId, "Harness", inferenceId, runId, false);
    }

    /**
     * 构造系统任务的结算身份。
     *
     * <p>进程内没有原始访问者的任务使用域 {@code system} 、Contact ID 0，Console 的 Contact 账单
     * 会排除这些记录，但它们仍参与全局与模块维度的统计。</p>
     *
     * @param module 模块名。
     * @return 结算身份，{@code delegated} 为 {@code false} 。
     */
    public static UsageContext system(String module) {
        return new UsageContext("system", 0, module, "", "", false);
    }

    /**
     * 生成委托副本。
     *
     * <p>用于把身份透传给执行单元：下游据此仍能感知真实归属，但记账动作留在发起方。</p>
     *
     * @return 除 {@code delegated} 置为 {@code true} 外，字段与本实例相同的副本。
     */
    public UsageContext delegate() {
        return new UsageContext(domain, contactId, module, requestId, runId, true);
    }
}
