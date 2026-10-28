package cube.aigc.usage;

import cube.auth.AuthToken;

/** Trusted, explicit accounting identity. Never deserialize this from a REST body. */
public final class UsageContext {
    public final String domain, module, requestId, runId;
    public final long contactId;
    public final boolean delegated;

    private UsageContext(String domain, long contactId, String module, String requestId, String runId, boolean delegated) {
        if (domain == null || domain.isEmpty() || domain.length() > 128 || contactId < 0) throw new IllegalArgumentException("INVALID_USAGE_IDENTITY");
        this.domain = domain; this.contactId = contactId; this.module = module;
        this.requestId = requestId == null ? "" : requestId; this.runId = runId == null ? "" : runId; this.delegated = delegated;
    }
    public static UsageContext visitor(AuthToken token, String module, String requestId) {
        long contact = 0;
        try { if (token != null) contact = token.getContactId(); }
        catch (NullPointerException unboundToken) { /* Older tokens may have no Contact. */ }
        if (token == null || contact <= 0 || token.getExpiry() <= System.currentTimeMillis())
            throw new IllegalArgumentException("CONTACT_REQUIRED_FOR_AI_USAGE");
        return new UsageContext(token.getDomain(), contact, module, requestId, "", false);
    }
    public static UsageContext run(String domain, long contactId, String runId, String inferenceId) {
        if (contactId <= 0) throw new IllegalArgumentException("CONTACT_REQUIRED_FOR_AI_USAGE");
        return new UsageContext(domain, contactId, "Harness", inferenceId, runId, false);
    }
    public static UsageContext system(String module) { return new UsageContext("system", 0, module, "", "", false); }
    public UsageContext delegate() { return new UsageContext(domain, contactId, module, requestId, runId, true); }
}
