package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Immutable, bounded index. ACL filtering precedes matching, counts and excerpts. */
public class KnowledgeProvider extends AbstractProvider {
    private final JSONArray documents;
    private final String digest;
    public KnowledgeProvider(Properties p) throws Exception {
        super("knowledge", "common.knowledge.search", "knowledge.read", p);
        Path path = Paths.get(p.getProperty("knowledge.index", ""));
        if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(path.toRealPath()))
            throw new IllegalArgumentException("KNOWLEDGE_INDEX_INVALID");
        byte[] raw;
        try (InputStream in = Files.newInputStream(path)) { raw = RuntimeSupport.read(in, 262144); }
        digest = RuntimeSupport.sha(raw);
        if (!digest.equals(p.getProperty("knowledge.index.sha256")))
            throw new IllegalArgumentException("KNOWLEDGE_INDEX_DIGEST_MISMATCH");
        documents = StrictJson.object(raw).getJSONArray("documents");
        if (documents.length() > 128) throw new IllegalArgumentException("KNOWLEDGE_INDEX_LIMIT");
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < documents.length(); i++) {
            JSONObject d = documents.getJSONObject(i);
            RuntimeSupport.id(d.getString("tenant")); RuntimeSupport.id(d.getString("id"));
            if (!keys.add(d.getString("tenant") + ":" + d.getString("id"))
                    || d.getString("text").length() > 8192 || d.getString("source").length() > 512
                    || d.getJSONArray("subjects").length() > 32)
                throw new IllegalArgumentException("KNOWLEDGE_DOCUMENT_INVALID");
            for (int n = 0; n < d.getJSONArray("subjects").length(); n++)
                RuntimeSupport.id(d.getJSONArray("subjects").getString(n));
        }
    }
    public void validate(JSONObject args) {
        super.validate(args);
        if (args.getString("query").trim().isEmpty()) throw new StoreException("KNOWLEDGE_QUERY_EMPTY");
    }
    protected JSONObject execute(ProviderInvocation c, JSONObject args) {
        return lookup(c.tenant, c.subject, args, c::cancelled);
    }
    JSONObject lookup(String tenant, String subject, JSONObject args, BooleanSupplier cancelled) {
        validate(args);
        String query = args.getString("query").trim();
        JSONArray citations = new JSONArray();
        boolean truncated = false;
        for (int i = 0; i < documents.length(); i++) {
            if (cancelled.getAsBoolean()) throw new StoreException("TOOL_CANCELLED");
            JSONObject d = documents.getJSONObject(i);
            if (!tenant.equals(d.getString("tenant"))
                    || !RuntimeSupport.strings(d.getJSONArray("subjects")).contains(subject)) continue;
            String text = d.getString("text");
            int at = -1;
            // regionMatches preserves offsets; lowercasing can expand Unicode characters.
            for (int pos = 0; pos <= text.length() - query.length(); pos++) {
                if (text.regionMatches(true, pos, query, 0, query.length())) { at = pos; break; }
            }
            if (at < 0) continue;
            if (citations.length() == 5) { truncated = true; break; }
            int start = Math.max(0, at - 128);
            if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
            citations.put(new JSONObject().put("documentId", d.getString("id")).put("source", d.getString("source"))
                    .put("sha256", RuntimeSupport.sha(text.getBytes(StandardCharsets.UTF_8)))
                    .put("text", Budget.prefix(text.substring(start), 2048)));
        }
        JSONObject out = new JSONObject().put("indexDigest", digest).put("trust", "untrusted-source-data")
                .put("citations", citations).put("truncated", truncated);
        Budget.items(out, "citations", resultBytes);
        return out;
    }
}
