/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cube.auth.AuthToken;
import cube.auth.AuthTokenProvider;
import cube.service.harness.runtime.ProfileRegistry;

import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * 仅从 Cube Auth 模块取得用户身份，不接受客户端自报的域、用户或角色。
 */
final class HarnessIdentity {
    final ProfileRegistry.Identity user;
    final long expires;

    private HarnessIdentity(ProfileRegistry.Identity user, long expires) {
        this.user = user;
        this.expires = expires;
    }

    static Set<String> permissions(Properties p) {
        Set<String> result = new TreeSet<>();
        for (String value : p.getProperty("execution.permissions",
                "skill.list,skill.activate,resource.list,resource.read,resource.search").split(",")) {
            if (!value.trim().isEmpty()) result.add(cube.service.harness.runtime.RuntimeSupport.id(value.trim()));
        }
        return Collections.unmodifiableSet(result);
    }

    static HarnessIdentity authenticate(AuthTokenProvider provider, String code, Set<String> permissions) {
        if (code == null || code.isEmpty() || code.length() > 4096)
            throw new HarnessBusiness.Failure(401, "UNAUTHORIZED");
        if (provider == null) throw new HarnessBusiness.Failure(503, "AUTH_UNAVAILABLE");
        final AuthToken token;
        try {
            token = provider.getToken(code);
        } catch (Exception e) {
            throw new HarnessBusiness.Failure(503, "AUTH_UNAVAILABLE");
        }
        try {
            if (token == null || !HarnessService.equal(code, token.getCode())
                    || token.getExpiry() <= System.currentTimeMillis() || token.getContactId() <= 0)
                throw new IllegalArgumentException();
            return new HarnessIdentity(new ProfileRegistry.Identity(token.getDomain(),
                    Long.toString(token.getContactId()), permissions), token.getExpiry());
        } catch (Exception e) {
            throw new HarnessBusiness.Failure(401, "UNAUTHORIZED");
        }
    }

    boolean allowed(Properties config, String key) {
        String principal = user.tenant + "/" + user.subject;
        for (String value : config.getProperty(key, "").split(","))
            if (principal.equals(value.trim())) return true;
        return false;
    }
}
