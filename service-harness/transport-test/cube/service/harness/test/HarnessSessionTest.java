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

/** Two real Cell sessions: transfer tokens cannot be used by another connection. */
public final class HarnessSessionTest {
    static final class HarnessScope extends Scope { HarnessScope() { cellets.add("Harness"); } }
    static final class Client implements AutoCloseable {
        final Nucleus nucleus = new Nucleus();
        final Performer performer = new Performer(nucleus);
        final Director director;
        final String credential;
        Client(int port, String credential) throws Exception {
            this.credential = credential;
            nucleus.getTalkService().addListener(performer);
            director = performer.addDirector("127.0.0.1", port, "127.0.0.1", 0, new HarnessScope());
            director.speaker = nucleus.getTalkService().call("127.0.0.1", port);
            long end = System.currentTimeMillis() + 15000;
            while (!director.speaker.isReady() && System.currentTimeMillis() < end) Thread.sleep(25);
            check(director.speaker.isReady(), "Cell handshake timed out");
        }
        JSONObject call(HarnessAction action, JSONObject data, byte[] raw, int status) {
            data.put("credential", credential);
            cell.core.talk.dialect.ActionDialect request = new Packet(action.name, data).toDialect();
            if (raw != null) request.addParam("body", raw);
            cell.core.talk.dialect.ActionDialect response = performer.syncTransmit(director, "Harness", request, 5000);
            check(response != null, "Missing correlated response");
            JSONObject reply = new Packet(response).data;
            check(reply.getInt("status") == status, action + ": " + reply);
            return reply.getJSONObject("body");
        }
        @Override public void close() { nucleus.destroy(); }
    }
    static void check(boolean value, String text) { if (!value) throw new AssertionError(text); }
    static JSONObject token(String id) { return new JSONObject().put("transferId", id); }

    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) { config.load(in); }
        try (Client a = new Client(Integer.parseInt(args[1]), config.getProperty("fixture.user.token"));
             Client b = new Client(Integer.parseInt(args[1]), config.getProperty("fixture.user.token"))) {
            JSONObject body = new JSONObject().put("fileName", "session.bin").put("mediaType", "application/octet-stream")
                    .put("size", 3).put("sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            String file = a.call(HarnessAction.CreateInput, new JSONObject().put("key", UUID.randomUUID().toString())
                    .put("contentType", "application/json"), body.toString().getBytes("UTF-8"), 201).getString("inputId");
            String transfer = a.call(HarnessAction.UploadInput, new JSONObject().put("id", file)
                    .put("key", "session-upload").put("contentType", "application/octet-stream"), null, 200).getString("transferId");
            b.call(HarnessAction.TransferResult, token(transfer), null, 404);
            b.call(HarnessAction.StartDownload, token(transfer), null, 404);
            b.call(HarnessAction.AbortTransfer, token(transfer), null, 404);
            try (PrimitiveOutputStream out = b.performer.openStream(b.director, "Harness", transfer)) { out.write("abc".getBytes("UTF-8")); }
            Thread.sleep(100);
            check(!a.call(HarnessAction.TransferResult, token(transfer), null, 200).getBoolean("complete"), "Foreign stream claimed upload");
            try (PrimitiveOutputStream out = a.performer.openStream(a.director, "Harness", transfer)) { out.write("abc".getBytes("UTF-8")); }
            long end = System.currentTimeMillis() + 5000;
            JSONObject result;
            do {
                result = a.call(HarnessAction.TransferResult, token(transfer), null, 200);
                if (result.getBoolean("complete")) break;
                Thread.sleep(25);
            } while (System.currentTimeMillis() < end);
            check(result.getBoolean("complete") && result.getJSONObject("response").getInt("status") == 200, "Owner upload failed");
            a.call(HarnessAction.AbortTransfer, token(transfer), null, 200);
            a.call(HarnessAction.TransferResult, token(transfer), null, 404);
            // A prepared download holds a pin until this owning session disconnects.
            a.call(HarnessAction.DownloadArtifact, new JSONObject().put("id", args[2]), null, 200);
        }
        System.out.println("PASS: real Cell cross-session controls/streams rejected, owning upload committed, disconnect releases prepared download");
    }
}
