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
     * 索引文件的字节上限：256 KiB。
     */
    private static final int MAX_INDEX_BYTES = 262144;

    /**
     * 索引允许包含的最大文档数。
     */
    private static final int MAX_DOCUMENTS = 128;

    /**
     * 单篇文档正文的字符上限。
     */
    private static final int MAX_TEXT_CHARS = 8192;

    /**
     * 单篇文档来源标识的字符上限。
     */
    private static final int MAX_SOURCE_CHARS = 512;

    /**
     * 单篇文档允许授权的主体数上限。
     */
    private static final int MAX_SUBJECTS = 32;

    /**
     * 单次检索返回的最大引用条数。
     */
    private static final int MAX_CITATIONS = 5;

    /**
     * 摘录时保留的命中点前文字符数。
     */
    private static final int EXCERPT_LEAD = 128;

    /**
     * 单条引用的字符上限。
     */
    private static final int MAX_EXCERPT_CHARS = 2048;

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
            raw = RuntimeSupport.read(in, MAX_INDEX_BYTES);
        }
        this.digest = RuntimeSupport.sha(raw);
        if (!this.digest.equals(p.getProperty("knowledge.index.sha256"))) {
            throw new IllegalArgumentException("KNOWLEDGE_INDEX_DIGEST_MISMATCH");
        }
        this.documents = StrictJson.object(raw).getJSONArray("documents");
        if (this.documents.length() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("KNOWLEDGE_INDEX_LIMIT");
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < this.documents.length(); i++) {
            JSONObject d = this.documents.getJSONObject(i);
            RuntimeSupport.id(d.getString("tenant"));
            RuntimeSupport.id(d.getString("id"));
            // 租户内文档 id 必须唯一。
            String key = d.getString("tenant") + ":" + d.getString("id");
            if (!keys.add(key)) {
                throw new IllegalArgumentException("KNOWLEDGE_DOCUMENT_INVALID");
            }
            if (d.getString("text").length() > MAX_TEXT_CHARS
                    || d.getString("source").length() > MAX_SOURCE_CHARS
                    || d.getJSONArray("subjects").length() > MAX_SUBJECTS) {
                throw new IllegalArgumentException("KNOWLEDGE_DOCUMENT_INVALID");
            }
            JSONArray subjects = d.getJSONArray("subjects");
            for (int n = 0; n < subjects.length(); n++) {
                RuntimeSupport.id(subjects.getString(n));
            }
        }
    }

    /**
     * 校验调用参数：除通用校验外，查询词不能为空。
     *
     * @param args 调用参数。
     */
    @Override
    public void validate(JSONObject args) {
        super.validate(args);
        if (args.getString("query").trim().isEmpty()) {
            throw new StoreException("KNOWLEDGE_QUERY_EMPTY");
        }
    }

    /**
     * 执行检索。
     *
     * @param context Broker 提供的可信调用上下文。
     * @param args    调用参数。
     * @return 返回检索结果，含引用列表与是否截断。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) {
        return lookup(context.tenant, context.subject, args, context::cancelled);
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
        for (int i = 0; i < this.documents.length(); i++) {
            if (cancelled.getAsBoolean()) {
                throw new StoreException("TOOL_CANCELLED");
            }
            JSONObject d = this.documents.getJSONObject(i);
            // 先判断可见性，再判断命中：不可见的文档不参与命中计数。
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
            if (citations.length() == MAX_CITATIONS) {
                truncated = true;
                break;
            }
            int start = Math.max(0, at - EXCERPT_LEAD);
            // 起点若落在低位代理字符上，退回一个字符保持代理对完整。
            if (start > 0 && Character.isLowSurrogate(text.charAt(start))) {
                start--;
            }
            String excerpt = Budget.prefix(text.substring(start), MAX_EXCERPT_CHARS);
            citations.put(new JSONObject()
                    .put("documentId", d.getString("id"))
                    .put("source", d.getString("source"))
                    .put("sha256", RuntimeSupport.sha(text.getBytes(StandardCharsets.UTF_8)))
                    .put("text", excerpt));
        }
        JSONObject out = new JSONObject()
                .put("indexDigest", this.digest)
                .put("trust", "untrusted-source-data")
                .put("citations", citations)
                .put("truncated", truncated);
        Budget.items(out, "citations", this.resultBytes);
        return out;
    }
}
