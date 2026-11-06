/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.mgmt;

import org.json.JSONObject;

/**
 * 单元概览接口的访问守卫。
 *
 * <p>守卫做两件事，都不依赖统计库，因此可以在鉴权之前执行：</p>
 * <ul>
 *     <li><b>双维限流</b>：用户维按令牌共享一个配额（同一用户的多个标签页不重复计费），
 *         IP 维作为兜底，挡住「换令牌绕过用户配额」的行为；</li>
 *     <li><b>鉴权开关</b>：单元台账是运营数据，默认要求登录态，
 *         便于在「公开看板」形态下退回到匿名可读。</li>
 * </ul>
 */
public class UnitAccessGuard {

    /**
     * 用户维限流器。
     */
    private final RateLimiter userLimiter;

    /**
     * IP 维限流器。
     */
    private final RateLimiter ipLimiter;

    /**
     * 是否要求登录态。
     */
    private final boolean requireAuth;

    /**
     * 构造守卫。
     *
     * @param requireAuth   是否要求登录态。
     * @param userPerMinute 用户维每分钟允许的次数。
     * @param userBurst     用户维允许的突发量。
     * @param ipPerMinute   IP 维每分钟允许的次数。
     * @param ipBurst       IP 维允许的突发量。
     */
    public UnitAccessGuard(boolean requireAuth, int userPerMinute, int userBurst,
                           int ipPerMinute, int ipBurst) {
        this.requireAuth = requireAuth;
        this.userLimiter = new RateLimiter(userPerMinute, userBurst);
        this.ipLimiter = new RateLimiter(ipPerMinute, ipBurst);
    }

    /**
     * 判定一次访问是否放行。
     * <p>两个维度都必须有余额，任一维度耗尽即拒绝。</p>
     *
     * @param userKey 用户维键，通常是访问令牌。
     * @param ipKey   IP 维键。
     * @return 放行返回 {@code true} 。
     */
    public boolean allow(String userKey, String ipKey) {
        if (!this.ipLimiter.tryAcquire(ipKey)) {
            return false;
        }
        return this.userLimiter.tryAcquire(userKey);
    }

    /**
     * 计算还需等待多少秒可以再次访问。
     *
     * @param userKey 用户维键。
     * @param ipKey   IP 维键。
     * @return 等待秒数，最小为 1。
     */
    public long retryAfterSeconds(String userKey, String ipKey) {
        long user = this.userLimiter.retryAfterSeconds(userKey);
        long ip = this.ipLimiter.retryAfterSeconds(ipKey);
        return Math.max(user, ip);
    }

    /**
     * 是否要求登录态。
     *
     * @return 要求登录态返回 {@code true} 。
     */
    public boolean isRequireAuth() {
        return this.requireAuth;
    }

    /**
     * 采集守卫指标。
     *
     * @return 指标快照。
     */
    public JSONObject metrics() {
        JSONObject json = new JSONObject();
        json.put("requireAuth", this.requireAuth);
        json.put("userRejected", this.userLimiter.getRejectedCount());
        json.put("userBuckets", this.userLimiter.getBucketCount());
        json.put("ipRejected", this.ipLimiter.getRejectedCount());
        json.put("ipBuckets", this.ipLimiter.getBucketCount());
        return json;
    }
}
