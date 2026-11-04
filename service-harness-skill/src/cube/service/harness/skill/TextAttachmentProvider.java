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
import java.util.HashSet;
import java.util.Properties;

/**
 * 文本附件读取技能：只接受纯文本类附件，并按字节预算截断。
 */
public class TextAttachmentProvider extends AbstractProvider {
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
        maxBytes = number(p, "attachment.max.bytes", 16384, 1, 16384);
    }

    /**
     * 读取附件内容。
     *
     * @param c    Broker 提供的可信调用上下文。
     * @param args 调用参数，含 inputId 。
     * @return 返回附件内容。
     * @throws Exception 读取附件过程中的异常。
     */
    protected JSONObject execute(ProviderInvocation c, JSONObject args) throws Exception {
        byte[] bytes = c.input(args.getString("inputId"), maxBytes,
                new HashSet<>(Arrays.asList("text/plain", "text/csv", "application/json")));
        return content(args.getString("inputId"), bytes);
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
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new StoreException("ATTACHMENT_TEXT_INVALID");
        }
        if (bytes.length > maxBytes) {
            throw new StoreException("ATTACHMENT_SIZE_LIMIT");
        }
        // 含空字符或以压缩包、PDF 头开头的附件不是文本，一律拒绝。
        if (text.indexOf('\u0000') >= 0 || text.startsWith("PK\u0003\u0004") || text.startsWith("%PDF")) {
            throw new StoreException("ATTACHMENT_FORMAT_FORBIDDEN");
        }
        JSONObject out = new JSONObject().put("inputId", id).put("sha256", RuntimeSupport.sha(bytes))
                .put("trust", "untrusted-source-data").put("text", text).put("originalBytes", bytes.length)
                .put("truncated", false);
        Budget.text(out, "text", resultBytes);
        return out;
    }
}
