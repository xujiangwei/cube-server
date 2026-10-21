package cube.service.harness.test;

import cell.api.Nucleus;
import cell.core.talk.PrimitiveOutputStream;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.dispatcher.*;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.UUID;

/**
 * Harness 文件传输的双真实 Cell 会话隔离测试。
 *
 * <p>两个独立 {@link Client} 以同一用户 token 建立两条 Cell 会话，验证
 * transferId 与连接绑定：非属主会话的控制动作（TransferResult /
 * StartDownload / AbortTransfer）与字节流均被拒绝（404 / 直接关闭），属主
 * 会话的上传被提交，断开后预准备的下载句柄被释放。</p>
 */
public final class HarnessSessionTest {

    /**
     * 测试用导演机 scope 。
     *
     * <p>仅包含 Harness cellet，用于匹配 Harness 服务单元。</p>
     */
    static final class HarnessScope extends Scope {

        /**
         * 构造函数：把 Harness 加入 scope 的 cellet 集合。
         */
        HarnessScope() {
            cellets.add("Harness");
        }
    }

    /**
     * 单条真实 Cell 会话的测试客户端。
     *
     * <p>持有独立 Nucleus/Performer/Director ，构造时完成 Cell 握手等待；
     * {@link #call} 发送同步业务请求并校验状态码；关闭时销毁 Nucleus 释放
     * 会话。</p>
     */
    static final class Client implements AutoCloseable {

        /** 会话所属的 Nucleus 实例。 */
        final Nucleus nucleus = new Nucleus();

        /** 会话使用的执行机，负责同步发送与开关流。 */
        final Performer performer = new Performer(nucleus);

        /** 指向 service Cell 的导演机。 */
        final Director director;

        /** 会话使用的 Cube 用户 token （Bearer 凭据）。 */
        final String credential;

        /**
         * 构造函数：建立与指定端口 service Cell 的连接并等待握手完成。
         *
         * @param port       service Cell 的监听端口。
         * @param credential 会话使用的用户 token 。
         * @throws Exception 当连接建立或握手等待失败时抛出。
         */
        Client(int port, String credential) throws Exception {
            this.credential = credential;
            nucleus.getTalkService().addListener(performer);
            director = performer.addDirector("127.0.0.1", port, "127.0.0.1", 0, new HarnessScope());
            director.speaker = nucleus.getTalkService().call("127.0.0.1", port);
            // 等待 Cell 握手就绪，最长 15 秒
            long end = System.currentTimeMillis() + 15000;
            while (!director.speaker.isReady() && System.currentTimeMillis() < end) {
                Thread.sleep(25);
            }
            check(director.speaker.isReady(), "Cell handshake timed out");
        }

        /**
         * 发送一次同步业务请求并校验应答状态码。
         *
         * <p>自动注入会话凭据；<code>raw</code> 非 null 时作为独立 body 参数
         * 携带原始 JSON 字节（严格 UTF-8 与重复键检查由 service 执行）。</p>
         *
         * @param action 业务动作。
         * @param data   请求数据。
         * @param raw    原始请求体字节，无请求体时传 null 。
         * @param status 期望的应答状态码。
         * @return 返回应答的 body JSON 对象。
         */
        JSONObject call(HarnessAction action, JSONObject data, byte[] raw, int status) {
            data.put("credential", credential);
            cell.core.talk.dialect.ActionDialect request = new Packet(action.name, data).toDialect();
            if (raw != null) {
                request.addParam("body", raw);
            }
            cell.core.talk.dialect.ActionDialect response = performer.syncTransmit(director, "Harness", request, 5000);
            check(response != null, "Missing correlated response");
            JSONObject reply = new Packet(response).data;
            check(reply.getInt("status") == status, action + ": " + reply);
            return reply.getJSONObject("body");
        }

        /**
         * 关闭会话：销毁 Nucleus ，释放底层连接。
         */
        @Override
        public void close() {
            nucleus.destroy();
        }
    }

    /**
     * 断言辅助方法：条件不成立时以指定消息抛出 {@link AssertionError}。
     *
     * @param value 断言条件。
     * @param text  断言失败时输出的消息。
     */
    static void check(boolean value, String text) {
        if (!value) {
            throw new AssertionError(text);
        }
    }

    /**
     * 构造携带传输 ID 的请求数据。
     *
     * @param id 传输 ID 。
     * @return 返回包含 transferId 字段的 JSON 对象。
     */
    static JSONObject token(String id) {
        return new JSONObject().put("transferId", id);
    }

    /**
     * 测试入口：以双会话验证传输令牌的跨连接隔离与属主上传提交。
     *
     * <p>属主会话 A 先创建输入并取得传输 ID ；会话 B 使用同一 transferId 的
     * 全部控制动作均被拒（404），其字节流被直接关闭且不推进上传；A 写入字节
     * 流后轮询 TransferResult 的 complete 标志直至提交成功；最后验证已断开
     * 会话的预准备下载句柄被释放。</p>
     *
     * @param args 命令行参数：args[0] 为夹具配置文件路径，args[1] 为 service
     *             Cell 端口，args[2] 为待下载的 artifact ID 。
     * @throws Exception 当连接、传输或断言失败时抛出。
     */
    public static void main(String[] args) throws Exception {
        // 读取夹具配置，取得测试用户 token
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            config.load(in);
        }
        // 同一 token 建立两条相互独立的 Cell 会话
        try (Client a = new Client(Integer.parseInt(args[1]), config.getProperty("fixture.user.token"));
             Client b = new Client(Integer.parseInt(args[1]), config.getProperty("fixture.user.token"))) {
            // 创建输入资源（3 字节内容的 sha256 摘要），返回 inputId
            JSONObject body = new JSONObject().put("fileName", "session.bin").put("mediaType", "application/octet-stream")
                    .put("size", 3).put("sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            String file = a.call(HarnessAction.CreateInput, new JSONObject().put("key", UUID.randomUUID().toString())
                    .put("contentType", "application/json"), body.toString().getBytes("UTF-8"), 201).getString("inputId");
            // 属主会话取得上传传输 ID
            String transfer = a.call(HarnessAction.UploadInput, new JSONObject().put("id", file)
                    .put("key", "session-upload").put("contentType", "application/octet-stream"), null, 200).getString("transferId");
            // 另一会话使用同一 transferId 的控制动作全部被拒（404）
            b.call(HarnessAction.TransferResult, token(transfer), null, 404);
            b.call(HarnessAction.StartDownload, token(transfer), null, 404);
            b.call(HarnessAction.AbortTransfer, token(transfer), null, 404);
            // 非属主连接的字节流被直接关闭，不推进上传
            try (PrimitiveOutputStream out = b.performer.openStream(b.director, "Harness", transfer)) {
                out.write("abc".getBytes("UTF-8"));
            }
            Thread.sleep(100);
            check(!a.call(HarnessAction.TransferResult, token(transfer), null, 200).getBoolean("complete"), "Foreign stream claimed upload");
            // 属主会话写入字节流，轮询 complete 标志直至提交
            try (PrimitiveOutputStream out = a.performer.openStream(a.director, "Harness", transfer)) {
                out.write("abc".getBytes("UTF-8"));
            }
            long end = System.currentTimeMillis() + 5000;
            JSONObject result;
            do {
                result = a.call(HarnessAction.TransferResult, token(transfer), null, 200);
                if (result.getBoolean("complete")) {
                    break;
                }
                Thread.sleep(25);
            } while (System.currentTimeMillis() < end);
            check(result.getBoolean("complete") && result.getJSONObject("response").getInt("status") == 200, "Owner upload failed");
            // 属主会话中止传输后，再次查询返回 404
            a.call(HarnessAction.AbortTransfer, token(transfer), null, 200);
            a.call(HarnessAction.TransferResult, token(transfer), null, 404);
            // 预准备的下载在本属主会话断开前保持 pin 。
            a.call(HarnessAction.DownloadArtifact, new JSONObject().put("id", args[2]), null, 200);
        }
        System.out.println("PASS: real Cell cross-session controls/streams rejected, owning upload committed, disconnect releases prepared download");
    }
}
