package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.json.JSONObject;

public class TextAttachmentProvider extends AbstractProvider {
    private final int maxBytes;
    public TextAttachmentProvider(Properties p) {
        super("attachment", "common.attachment.read", "attachment.read", p);
        maxBytes = number(p, "attachment.max.bytes", 16384, 1, 16384);
    }
    protected JSONObject execute(ProviderInvocation c, JSONObject args) throws Exception {
        byte[] bytes = c.input(args.getString("inputId"), maxBytes,
                new HashSet<>(Arrays.asList("text/plain", "text/csv", "application/json")));
        return content(args.getString("inputId"), bytes);
    }
    JSONObject content(String id, byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new StoreException("ATTACHMENT_TEXT_INVALID"); }
        if (bytes.length > maxBytes) throw new StoreException("ATTACHMENT_SIZE_LIMIT");
        if (text.indexOf('\u0000') >= 0 || text.startsWith("PK\u0003\u0004") || text.startsWith("%PDF"))
            throw new StoreException("ATTACHMENT_FORMAT_FORBIDDEN");
        JSONObject out = new JSONObject().put("inputId", id).put("sha256", RuntimeSupport.sha(bytes))
                .put("trust", "untrusted-source-data").put("text", text).put("originalBytes", bytes.length)
                .put("truncated", false);
        Budget.text(out, "text", resultBytes);
        return out;
    }
}
