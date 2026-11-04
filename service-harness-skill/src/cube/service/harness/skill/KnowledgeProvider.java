/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 知识库检索技能：不可变且有界的索引。
 *
 * <p>先做 ACL 过滤，再做匹配、计数与摘录，避免通过命中数泄露他人文档。</p>
 */
public class KnowledgeProvider extends AbstractProvider {
    /**
     * 索引中的全部文档。
     */
    private final JSONArray documents;
    /**
     * 索引文件摘要，用于结果溯源。
     */
    private final String digest;

    /**
     * 构造函数：加载并校验本地索引文件。
     *
     * @param p 技能私有配置，包含索引路径与期望摘要。
     * @throws Exception 读取索引文件过程中的异常。
     */
    public KnowledgeProvider(Properties p) throws Exception {
        super("knowledge", "common.knowledge.search", "knowledge.read", p);
        Path path = Paths.get(p.getProperty("knowledge.index", ""));
        // 索引必须是绝对路径的普通文件，且不能经过符号链接。
        if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(path.toRealPath())) {
            throw new IllegalArgumentException("KNOWLEDGE_INDEX_INVALID");
        }
        byte[] raw;
        try (InputStream in = Files.newInputStream(path)) {
            raw = RuntimeSupport.read(in, 262144);
        }
        digest = RuntimeSupport.sha(raw);
        if (!digest.equals(p.getProperty("knowledge.index.sha256"))) {
            throw new IllegalArgumentException("KNOWLEDGE_INDEX_DIGEST_MISMATCH");
        }
        documents = StrictJson.object(raw).getJSONArray("documents");
        if (documents.length() > 128) {
            throw new IllegalArgumentException("KNOWLEDGE_INDEX_LIMIT");
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < documents.length(); i++) {
            JSONObject d = documents.getJSONObject(i);
            RuntimeSupport.id(d.getString("tenant"));
            RuntimeSupport.id(d.getString("id"));
            if (!keys.add(d.getString("tenant") + ":" + d.getString("id"))
                    || d.getString("text").length() > 8192 || d.getString("source").length() > 512
                    || d.getJSONArray("subjects").length() > 32) {
                throw new IllegalArgumentException("KNOWLEDGE_DOCUMENT_INVALID");
            }
            for (int n = 0; n < d.getJSONArray("subjects").length(); n++) {
                RuntimeSupport.id(d.getJSONArray("subjects").getString(n));
            }
        }
    }

    /**
     * 校验调用参数：除通用校验外，查询词不能为空。
     *
     * @param args 调用参数。
     */
    public void validate(JSONObject args) {
        super.validate(args);
        if (args.getString("query").trim().isEmpty()) {
            throw new StoreException("KNOWLEDGE_QUERY_EMPTY");
        }
    }

    /**
     * 执行检索。
     *
     * @param c    Broker 提供的可信调用上下文。
     * @param args 调用参数。
     * @return 返回检索结果，含引用列表与是否截断。
     */
    protected JSONObject execute(ProviderInvocation c, JSONObject args) {
        return lookup(c.tenant, c.subject, args, c::cancelled);
    }

    /**
     * 按租户与主体做 ACL 过滤后匹配查询词，最多返回 5 条引用。
     *
     * @param tenant    当前租户。
     * @param subject   当前主体。
     * @param args      调用参数。
     * @param cancelled 取消标记。
     * @return 返回检索结果。
     */
    JSONObject lookup(String tenant, String subject, JSONObject args, BooleanSupplier cancelled) {
        validate(args);
        String query = args.getString("query").trim();
        JSONArray citations = new JSONArray();
        boolean truncated = false;
        for (int i = 0; i < documents.length(); i++) {
            if (cancelled.getAsBoolean()) {
                throw new StoreException("TOOL_CANCELLED");
            }
            JSONObject d = documents.getJSONObject(i);
            if (!tenant.equals(d.getString("tenant"))
                    || !RuntimeSupport.strings(d.getJSONArray("subjects")).contains(subject)) {
                continue;
            }
            String text = d.getString("text");
            int at = -1;
            // regionMatches 保留字符偏移；先转小写可能让 Unicode 字符长度变化。
            for (int pos = 0; pos <= text.length() - query.length(); pos++) {
                if (text.regionMatches(true, pos, query, 0, query.length())) {
                    at = pos;
                    break;
                }
            }
            if (at < 0) {
                continue;
            }
            if (citations.length() == 5) {
                truncated = true;
                break;
            }
            int start = Math.max(0, at - 128);
            if (start > 0 && Character.isLowSurrogate(text.charAt(start))) {
                start--;
            }
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
