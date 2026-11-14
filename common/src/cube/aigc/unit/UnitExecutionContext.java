package cube.aigc.unit;

import cube.aigc.usage.UsageContext;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Constructed by the trusted host after authentication; never parsed from tool arguments. */
public final class UnitExecutionContext {
    public final String tenant, subject, runId;
    public final UsageContext usage;
    private final Set<String> permissions;

    public UnitExecutionContext(String tenant, String subject, String runId, Set<String> permissions, UsageContext usage) {
        this.tenant = UnitJson.id(tenant);
        this.subject = UnitJson.id(subject);
        this.runId = UnitJson.id(runId);
        if (usage == null || !tenant.equals(usage.domain) || !runId.equals(usage.runId)
                || !subject.equals(Long.toString(usage.contactId)))
            throw new IllegalArgumentException("UNIT_USAGE_IDENTITY_MISMATCH");
        this.usage = usage;
        this.permissions = Collections.unmodifiableSet(new HashSet<>(permissions));
    }

    public boolean allows(String permission) { return permissions.contains(permission); }
}
