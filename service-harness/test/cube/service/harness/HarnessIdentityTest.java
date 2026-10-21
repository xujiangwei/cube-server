package cube.service.harness;

import cube.auth.AuthToken;
import cube.auth.AuthTokenProvider;
import java.util.*;

/**
 * Harness 身份认证的单体验证：覆盖 Cube Auth 令牌的身份提取、到期与未绑定
 * 联系人拒绝、code 不匹配拒绝、域白名单角色判定，以及 Auth 模块缺失或查询
 * 异常时的 503 语义。
 *
 * <p>所有拒绝分支统一经 {@link #denied} 走 {@link HarnessIdentity#authenticate}：
 * 期望失败必须抛出 {@link HarnessBusiness.Failure}，且 HTTP 状态码与入参
 * 一致；认证被接受或状态码不符都直接判 AssertionError。
 */
public final class HarnessIdentityTest {
    /**
     * 断言一次认证按预期拒绝。
     *
     * @param status 期望的 HTTP 状态码（401 未认证 / 503 Auth 不可用）
     * @param provider 受测的 Auth 令牌查询器；null 表示当前 JVM 无可用 Auth 模块
     * @param code 受测的 Bearer 令牌原文
     * @throws AssertionError 认证被接受，或 Failure 携带的状态码与 status 不符
     */
    private static void denied(int status, AuthTokenProvider provider, String code) {
        try {
            HarnessIdentity.authenticate(provider, code, Collections.emptySet());
            throw new AssertionError("Token accepted");
        } catch (HarnessBusiness.Failure e) {
            if (e.status != status) {
                throw new AssertionError("Wrong status: " + e.status);
            }
        }
    }

    /**
     * 测试入口：先验证正常身份提取与域白名单，再依次覆盖全部拒绝分支。
     *
     * @param args 未使用的命令行参数
     */
    public static void main(String[] args) {
        long now = System.currentTimeMillis();
        AuthToken token = new AuthToken("existing-cube-user-token", "example.com", "app", 12345L, now, now + 60000, false);
        HarnessIdentity identity = HarnessIdentity.authenticate(code -> token, token.getCode(), Collections.singleton("resource.read"));
        // 身份三要素：租户取 domain、主体取 contactId、权限取服务端配置传入值。
        if (!identity.user.tenant.equals("example.com") || !identity.user.subject.equals("12345")
                || !identity.user.permissions.equals(Collections.singleton("resource.read"))) {
            throw new AssertionError("Identity lost");
        }
        Properties p = new Properties();
        p.setProperty("control.approver.subjects", "other.com/12345, example.com/12345");
        // 白名单按 <域>/<联系人ID> 精确匹配：命中配置键所在列表，未配置的键一律不命中。
        if (!identity.allowed(p, "control.approver.subjects") || identity.allowed(p, "trace.reader.subjects")) {
            throw new AssertionError("Role scope");
        }
        // 查询不到令牌：按 401 拒绝。
        denied(401, code -> null, "unknown");
        // 令牌存在但与请求 code 不一致：按 401 拒绝。
        denied(401, code -> token, "different-code");
        // 已过期令牌（到期时间等于当前时刻）：按 401 拒绝。
        denied(401, code -> new AuthToken(code, "example.com", "app", 12345L, now, now, false), "expired");
        // 未绑定有效联系人（null / 0 / 负值 contactId）：按 401 拒绝。
        for (Long contact : new Long[]{null, 0L, -1L}) {
            denied(401, code -> new AuthToken(code, "example.com", "app", contact, now, now + 60000, false), "unbound");
        }
        // 域含非法字符（invalid/domain 无法构成合法身份）：按 401 拒绝。
        denied(401, code -> new AuthToken(code, "invalid/domain", "app", 12345L, now, now + 60000, false), "bad-domain");
        // 空 code：按 401 拒绝。
        denied(401, code -> token, "");
        // 无 Auth 模块（provider 为 null）：按 503 拒绝。
        denied(503, null, "valid-format");
        // Auth 查询过程抛异常：按 503 拒绝。
        denied(503, code -> { throw new IllegalStateException(); }, "valid-format");
        System.out.println("PASS: Cube token identity, expiry, missing user, mismatched code, domain-scoped roles and unavailable Auth");
    }
}
