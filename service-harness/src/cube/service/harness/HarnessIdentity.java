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
 * 身份三要素来自 AuthToken：租户取 domain、主体取 contactId、权限取服务端配置；
 * 令牌无效、过期或未绑定联系人一律按 401 拒绝，Auth 模块不可用按 503 拒绝。
 */
final class HarnessIdentity {
    /**
     * 认证后的用户身份：域与主体来自 token，权限集合来自服务端配置。
     */
    final ProfileRegistry.Identity user;

    /**
     * 令牌到期时间（epoch 毫秒）；传输类操作在令牌到期后中止。
     */
    final long expires;

    /**
     * 用已校验的 AuthToken 构造身份对象，仅供包内认证流程使用。
     *
     * @param user    用户身份，域与主体来自 token，权限来自服务端配置
     * @param expires 令牌到期时间（epoch 毫秒）
     */
    private HarnessIdentity(ProfileRegistry.Identity user, long expires) {
        this.user = user;
        this.expires = expires;
    }

    /**
     * 解析服务端配置的默认权限集合：逗号分隔，逐项归一化 ID，按字典序去重。
     * 未配置 execution.permissions 时回落到
     * skill.list,skill.activate,resource.list,resource.read,resource.search。
     *
     * @param p 已加载的私有配置，读取 execution.permissions 键
     * @return 不可变的权限 ID 集合，按字典序排列
     */
    static Set<String> permissions(Properties p) {
        Set<String> result = new TreeSet<>();
        for (String value : p.getProperty("execution.permissions",
                "skill.list,skill.activate,resource.list,resource.read,resource.search").split(",")) {
            if (!value.trim().isEmpty()) {
                result.add(cube.service.harness.runtime.RuntimeSupport.id(value.trim()));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 用 Cube Auth 校验 Bearer 令牌并取得用户身份；载荷自报身份一律不被采纳。
     * 令牌须与传入 code 完全一致、未过期且绑定有效联系人，否则拒绝。
     *
     * @param provider    Auth 模块的 token 查询器；为 null 表示当前 JVM 无可用 Cube Auth
     * @param code        请求携带的 Bearer 令牌原文；不可为空且不超过 4096 字符
     * @param permissions 授予该身份的权限集合（由服务端配置派生）
     * @return 认证成功的身份，含用户与到期时间
     * @throws HarnessBusiness.Failure 401/UNAUTHORIZED：code 缺失、超长、查询不到、不匹配、过期或未绑定联系人；
     *                                 503/AUTH_UNAVAILABLE：无 Auth 模块或查询过程抛出异常
     */
    static HarnessIdentity authenticate(AuthTokenProvider provider, String code, Set<String> permissions) {
        if (code == null || code.isEmpty() || code.length() > 4096) {
            throw new HarnessBusiness.Failure(401, "UNAUTHORIZED");
        }
        if (provider == null) {
            throw new HarnessBusiness.Failure(503, "AUTH_UNAVAILABLE");
        }
        // Auth 查询结果；为 null 或与 code 不匹配时按未认证拒绝。
        final AuthToken token;
        try {
            token = provider.getToken(code);
        } catch (Exception e) {
            throw new HarnessBusiness.Failure(503, "AUTH_UNAVAILABLE");
        }
        try {
            if (token == null || !HarnessService.equal(code, token.getCode())
                    || token.getExpiry() <= System.currentTimeMillis() || token.getContactId() <= 0) {
                throw new IllegalArgumentException();
            }
            return new HarnessIdentity(new ProfileRegistry.Identity(token.getDomain(),
                    Long.toString(token.getContactId()), permissions), token.getExpiry());
        } catch (Exception e) {
            throw new HarnessBusiness.Failure(401, "UNAUTHORIZED");
        }
    }

    /**
     * 判断当前身份是否命中指定白名单配置键。
     * 白名单条目为 {@code <域>/<联系人ID>}，逗号分隔，比较时忽略条目两侧空白。
     *
     * @param config 已加载的私有配置
     * @param key    白名单配置键，如 control.approver.subjects、trace.reader.subjects
     * @return 命中白名单返回 true，否则 false
     */
    boolean allowed(Properties config, String key) {
        String principal = user.tenant + "/" + user.subject;
        for (String value : config.getProperty(key, "").split(",")) {
            if (principal.equals(value.trim())) {
                return true;
            }
        }
        return false;
    }
}
