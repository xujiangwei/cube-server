package cube.dispatcher.aigc.handler;

import cube.aigc.SpecializedDetectionException;
import cube.common.entity.SpecializedDetectionInput;
import cube.dispatcher.aigc.Manager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Structured image requests and background video controls share one protocol. */
public final class SpecializedDetectionHandler extends ContextHandler {
    public SpecializedDetectionHandler(boolean stream) {
        super(stream ? "/specialized-detection/stream/" : "/specialized-detection/base/");
        setHandler(new Handler(stream));
    }

    private static final class Handler extends AIGCHandler {
        private final boolean stream;
        Handler(boolean stream) { this.stream = stream; }
        @Override public void doPost(HttpServletRequest request, HttpServletResponse response) {
            try {
                String token = Manager.getInstance().checkAndGetToken(getApiToken(request), getDevice(request));
                if (token == null) throw SpecializedDetectionException.unit(21);
                JSONObject body = readJson(request);
                String channel = body.getString("channel");
                if (channel.trim().isEmpty()) throw SpecializedDetectionException.invalid("channel is required");
                SpecializedDetectionInput input = new SpecializedDetectionInput(body.getJSONObject("input"));
                if (stream == "detect".equals(input.operation)) throw SpecializedDetectionException.invalid("Operation does not match detection endpoint");
                respondOk(response, Manager.getInstance().executeSpecializedDetection(token, channel, input));
            } catch (SpecializedDetectionException error) {
                respond(response, error.httpStatus, new JSONObject().put("error", error.toJSON()));
            } catch (org.json.JSONException | IllegalArgumentException error) {
                SpecializedDetectionException failure = SpecializedDetectionException.invalid("Invalid detection request");
                respond(response, failure.httpStatus, new JSONObject().put("error", failure.toJSON()));
            } catch (Exception error) {
                SpecializedDetectionException failure = SpecializedDetectionException.unit(31);
                respond(response, failure.httpStatus, new JSONObject().put("error", failure.toJSON()));
            } finally { complete(); }
        }

        private JSONObject readJson(HttpServletRequest request) throws java.io.IOException {
            final int limit = 64 * 1024;
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > limit)
                        throw new SpecializedDetectionException(41, 413, "REQUEST_TOO_LARGE", "Detection JSON exceeds 64 KiB");
                    bytes.write(buffer, 0, n);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        }
    }
}
