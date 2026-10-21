package cube.service.harness.test;

import cube.auth.AuthToken;
import cube.auth.AuthTokenProvider;
import cube.core.AbstractModule;
import cube.plugin.PluginSystem;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 测试专用 Auth 模块，不进入正式 jar。 */
public final class FixtureAuth extends AbstractModule implements AuthTokenProvider {
    /** 令牌表：code 到 AuthToken 的内存映射，并发安全（测试期多线程查询）。 */
    private final Map<String, AuthToken> tokens = new ConcurrentHashMap<>();

    /**
     * 登记一个测试用 Cube 用户令牌。
     *
     * <p>应用名固定为 fixture-app；生效时间为当前时刻，过期时间为当前
     * 时刻加 ttl（ttl 为负即过去时刻，用于构造已过期令牌）。
     *
     * @param code 令牌原文，同时作为查询键
     * @param domain 用户域（租户）
     * @param contact 联系人 ID（主体）
     * @param ttl 有效时长（毫秒）；负值表示已过期
     */
    public void add(String code, String domain, long contact, long ttl) {
        tokens.put(code, new AuthToken(code, domain, "fixture-app", contact,
                System.currentTimeMillis(), System.currentTimeMillis() + ttl, false));
    }

    /**
     * 按令牌原文查询 AuthToken。
     *
     * @param code 令牌原文
     * @return 对应的 AuthToken；不存在时返回 null
     */
    @Override
    public AuthToken getToken(String code) {
        return tokens.get(code);
    }

    /**
     * 模块启动：置已启动标志。
     */
    @Override
    public void start() {
        started.set(true);
    }

    /**
     * 模块停止：清已启动标志。
     */
    @Override
    public void stop() {
        started.set(false);
    }

    /**
     * 空实现：测试模块不参与 Kernel 周期 tick。
     *
     * @param module 注册该模块的模块实例
     * @param kernel 宿主 Kernel
     */
    @Override
    public void onTick(cube.core.Module module, cube.core.Kernel kernel) {
    }

    /**
     * 空实现：测试模块不挂接插件系统。
     *
     * @param <T> 插件系统类型
     * @return 固定返回 null
     */
    @Override
    public <T extends PluginSystem> T getPluginSystem() {
        return null;
    }
}
