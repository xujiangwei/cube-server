/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

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

/**
 * 专用检测端点。结构化图像请求与后台视频控制共用同一套协议，
 * 由构造参数决定挂载 base 路径还是 stream 路径。
 */
public final class SpecializedDetectionHandler extends ContextHandler {
    /**
     * 构造方法。
     *
     * @param stream 为 true 时挂载到 stream 路径，处理后台视频流操作；否则挂载到 base 路径
     */
    public SpecializedDetectionHandler(boolean stream) {
        super(stream ? "/specialized-detection/stream/" : "/specialized-detection/base/");
        setHandler(new Handler(stream));
    }

    /**
     * 实际的请求处理器。
     */
    private static final class Handler extends AIGCHandler {
        /**
         * 是否为 stream 端点。
         */
        private final boolean stream;

        /**
         * 构造方法。
         *
         * @param stream 是否为 stream 端点
         */
        Handler(boolean stream) {
            this.stream = stream;
        }

        /**
         * 处理检测请求：校验令牌与入参，交由管理器执行，并按异常类型映射为对应的 HTTP 状态码。
         *
         * @param request  HTTP 请求
         * @param response HTTP 响应
         */
        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response) {
            try {
                String token = Manager.getInstance().checkAndGetToken(getApiToken(request), getDevice(request));
                if (token == null) {
                    throw SpecializedDetectionException.unit(21);
                }
                JSONObject body = readJson(request);
                String channel = body.getString("channel");
                if (channel.trim().isEmpty()) {
                    throw SpecializedDetectionException.invalid("channel is required");
                }
                SpecializedDetectionInput input = new SpecializedDetectionInput(body.getJSONObject("input"));
                if (stream == "detect".equals(input.operation)) {
                    throw SpecializedDetectionException.invalid("Operation does not match detection endpoint");
                }
                respondOk(response, Manager.getInstance().executeSpecializedDetection(token, channel, input));
            } catch (SpecializedDetectionException error) {
                respond(response, error.httpStatus, new JSONObject().put("error", error.toJSON()));
            } catch (org.json.JSONException | IllegalArgumentException error) {
                SpecializedDetectionException failure = SpecializedDetectionException.invalid("Invalid detection request");
                respond(response, failure.httpStatus, new JSONObject().put("error", failure.toJSON()));
            } catch (Exception error) {
                SpecializedDetectionException failure = SpecializedDetectionException.unit(31);
                respond(response, failure.httpStatus, new JSONObject().put("error", failure.toJSON()));
            } finally {
                complete();
            }
        }

        /**
         * 读取请求体并解析为 JSON，请求体上限 64 KiB。
         *
         * @param request HTTP 请求
         * @return 请求体 JSON
         * @throws java.io.IOException           读取请求体失败时抛出
         * @throws SpecializedDetectionException 请求体超过上限时抛出
         */
        private JSONObject readJson(HttpServletRequest request) throws java.io.IOException {
            final int limit = 64 * 1024;
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > limit) {
                        throw new SpecializedDetectionException(41, 413, "REQUEST_TOO_LARGE", "Detection JSON exceeds 64 KiB");
                    }
                    bytes.write(buffer, 0, n);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        }
    }
}
