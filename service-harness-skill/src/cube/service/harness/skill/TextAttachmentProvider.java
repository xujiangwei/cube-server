/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 文本附件读取技能：只接受纯文本类附件，并按字节预算截断。
 */
public class TextAttachmentProvider extends AbstractProvider {
    /**
     * 允许读取的附件媒体类型。
     */
    private static final Set<String> TEXT_MEDIA_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("text/plain", "text/csv", "application/json")));

    /**
     * 附件字节数上限。
     */
    private final int maxBytes;

    /**
     * 构造函数。
     *
     * @param p 技能私有配置。
     */
    public TextAttachmentProvider(Properties p) {
        super("attachment", "common.attachment.read", "attachment.read", p);
        this.maxBytes = number(p, "attachment.max.bytes", 16384, 1, 16384);
    }

    /**
     * 读取附件内容。
     *
     * @param context Broker 提供的可信调用上下文。
     * @param args    调用参数，含 {@code inputId} 。
     * @return 返回附件内容。
     * @throws Exception 读取附件过程中的异常。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception {
        String inputId = args.getString("inputId");
        byte[] bytes = context.input(inputId, this.maxBytes, TEXT_MEDIA_TYPES);
        return content(inputId, bytes);
    }

    /**
     * 解码并校验附件内容。
     *
     * @param id    输入标识。
     * @param bytes 附件字节。
     * @return 返回附件内容，含摘要与文本。
     */
    JSONObject content(String id, byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new StoreException("ATTACHMENT_TEXT_INVALID");
        }
        if (bytes.length > this.maxBytes) {
            throw new StoreException("ATTACHMENT_SIZE_LIMIT");
        }
        // 含空字符或以压缩包、PDF 头开头的附件不是文本，一律拒绝。
        if (text.indexOf('\u0000') >= 0 || text.startsWith("PK\u0003\u0004") || text.startsWith("%PDF")) {
            throw new StoreException("ATTACHMENT_FORMAT_FORBIDDEN");
        }
        JSONObject out = new JSONObject()
                .put("inputId", id)
                .put("sha256", RuntimeSupport.sha(bytes))
                .put("trust", "untrusted-source-data")
                .put("text", text)
                .put("originalBytes", bytes.length)
                .put("truncated", false);
        Budget.text(out, "text", this.resultBytes);
        return out;
    }
}
