package cube.service.harness.test;

import cube.auth.AuthToken;
import cube.auth.AuthTokenProvider;
import cube.core.AbstractModule;
import cube.plugin.PluginSystem;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 测试专用 Auth 模块，不进入正式 jar。 */
public final class FixtureAuth extends AbstractModule implements AuthTokenProvider {
    private final Map<String, AuthToken> tokens = new ConcurrentHashMap<>();

    public void add(String code, String domain, long contact, long ttl) {
        tokens.put(code, new AuthToken(code, domain, "fixture-app", contact,
                System.currentTimeMillis(), System.currentTimeMillis() + ttl, false));
    }

    @Override public AuthToken getToken(String code) { return tokens.get(code); }
    @Override public void start() { started.set(true); }
    @Override public void stop() { started.set(false); }
    @Override public void onTick(cube.core.Module module, cube.core.Kernel kernel) { }
    @Override public <T extends PluginSystem> T getPluginSystem() { return null; }
}
