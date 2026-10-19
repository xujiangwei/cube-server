package cube.service.harness;

import cube.auth.AuthToken;
import cube.auth.AuthTokenProvider;
import java.util.*;

public final class HarnessIdentityTest {
    private static void denied(int status, AuthTokenProvider provider, String code) {
        try {
            HarnessIdentity.authenticate(provider, code, Collections.emptySet());
            throw new AssertionError("Token accepted");
        } catch (HarnessBusiness.Failure e) {
            if (e.status != status) throw new AssertionError("Wrong status: " + e.status);
        }
    }

    public static void main(String[] args) {
        long now = System.currentTimeMillis();
        AuthToken token = new AuthToken("existing-cube-user-token", "example.com", "app", 12345L, now, now + 60000, false);
        HarnessIdentity identity = HarnessIdentity.authenticate(code -> token, token.getCode(), Collections.singleton("resource.read"));
        if (!identity.user.tenant.equals("example.com") || !identity.user.subject.equals("12345")
                || !identity.user.permissions.equals(Collections.singleton("resource.read"))) throw new AssertionError("Identity lost");
        Properties p = new Properties();
        p.setProperty("control.approver.subjects", "other.com/12345, example.com/12345");
        if (!identity.allowed(p, "control.approver.subjects") || identity.allowed(p, "trace.reader.subjects")) throw new AssertionError("Role scope");
        denied(401, code -> null, "unknown");
        denied(401, code -> token, "different-code");
        denied(401, code -> new AuthToken(code, "example.com", "app", 12345L, now, now, false), "expired");
        for (Long contact : new Long[]{null, 0L, -1L})
            denied(401, code -> new AuthToken(code, "example.com", "app", contact, now, now + 60000, false), "unbound");
        denied(401, code -> new AuthToken(code, "invalid/domain", "app", 12345L, now, now + 60000, false), "bad-domain");
        denied(401, code -> token, "");
        denied(503, null, "valid-format");
        denied(503, code -> { throw new IllegalStateException(); }, "valid-format");
        System.out.println("PASS: Cube token identity, expiry, missing user, mismatched code, domain-scoped roles and unavailable Auth");
    }
}
