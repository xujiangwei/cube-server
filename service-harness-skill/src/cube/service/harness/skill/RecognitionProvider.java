/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Dimension;
import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 授权一次 run 附件，并把识别委托给既有宿主 Unit。
 */
public final class RecognitionProvider extends AbstractProvider {
    /**
     * 是否为 OCR 族。
     */
    private final boolean ocr;

    /**
     * 单次识别超时时间，单位毫秒。
     */
    private final int timeout;

    /**
     * 排队等待容量的超时时间，单位毫秒。
     */
    private final int queueTimeout;

    /**
     * 按配置族名构造 provider。
     *
     * @param config 技能私有配置。
     */
    public RecognitionProvider(Properties config) {
        this(config, config.getProperty("family", ""));
    }

    /**
     * 按族名构造 provider。
     *
     * @param config 技能私有配置。
     * @param family 族名，仅接受 ocr 或 transcription。
     */
    private RecognitionProvider(Properties config, String family) {
        super(family, "common." + family + ".recognize", family + ".recognize", config);
        if (!"ocr".equals(family) && !"transcription".equals(family)) {
            throw new IllegalArgumentException("RECOGNITION_FAMILY_INVALID");
        }
        ocr = "ocr".equals(family);
        timeout = number(config, "timeout.millis", ocr ? 90000 : 160000, 1000, 180000);
        queueTimeout = number(config, "queue.timeout.millis", 10000, 100, 30000);
    }

    /**
     * 返回 Broker 等待物理回执的最长毫秒数。
     *
     * @return 等待上限。
     */
    @Override
    public int runnerWaitMillis() {
        return timeout + queueTimeout + 5000;
    }

    /**
     * 对账物理执行状态。
     *
     * @param context 调用上下文。
     * @return Unit 侧已给出物理完成回执返回 true。
     */
    @Override
    public boolean reconcile(ProviderInvocation context) {
        return context.mediaFinished();
    }

    /**
     * 执行识别：取输入、预留 Unit、等待物理回执并产出文本产物。
     *
     * @param context 调用上下文。
     * @param args    调用参数。
     * @return 识别结果。
     * @throws Exception 读取输入或等待回执过程中的异常。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception {
        String id = RuntimeSupport.id(args.getString("inputId"));
        String type = args.getString("mediaType");
        String unit = ocr ? "OmniPerception" : "Speech";
        byte[] bytes = context.input(id, ocr ? 1048576 : 10485760, Collections.singleton(type));
        JSONObject option = ocr ? ocrOption(bytes, type, args) : new JSONObject();
        JSONObject intent = new JSONObject()
                .put("tenant", context.tenant)
                .put("subject", context.subject)
                .put("runId", context.runId)
                .put("mediaType", type)
                .put("sha256", RuntimeSupport.sha(bytes))
                .put("adapter", ocr ? "multimodal" : "speech-recognition")
                .put("content", "")
                .put("option", option);
        context.phase("queued", new JSONObject().put("unit", unit));
        long waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(queueTimeout);
        JSONObject execution;
        while (true) {
            check(context);
            long remaining = context.remainingMillis();
            if (remaining == 0) {
                throw new StoreException("TOOL_CANCELLED");
            }
            intent.put("deadlineAt", System.currentTimeMillis() + Math.min(timeout, remaining));
            try {
                execution = context.reserveMedia(unit, intent);
                break;
            } catch (StoreException e) {
                if (!"CAPABILITY_CAPACITY_UNAVAILABLE".equals(e.code)) {
                    throw e;
                }
                if (System.nanoTime() >= waitUntil) {
                    throw new StoreException("CAPABILITY_QUEUE_TIMEOUT");
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        context.invokeMedia(execution, bytes, future::complete);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeout, context.remainingMillis()));
        JSONObject receipt;
        try {
            for (; ; ) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
                if (context.cancelled() || remaining <= 0) {
                    throw new IOException("MEDIA_EXECUTION_UNKNOWN");
                }
                try {
                    receipt = future.get(Math.min(100, remaining), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException pending) {
                    // 等待物理回执期间保持同一个截止时间。
                }
            }
        } catch (IOException | InterruptedException stopped) {
            context.cancelMedia();
            throw stopped;
        }
        JSONObject payload = receipt.optJSONObject("payload");
        if (!receipt.optBoolean("success")) {
            throw new StoreException(payload != null && "CAPABILITY_NOT_READY".equals(payload.optString("errorCode"))
                    ? "CAPABILITY_NOT_READY" : "CAPABILITY_FAILED");
        }
        JSONObject result = recognitionResult(ocr, id, payload);
        String text = payload.getString(ocr ? "response" : "text");
        // 把完整识别文本作为 run 产物持久化；工具上下文只收到预览。
        if (!text.isEmpty()) {
            JSONObject file = context.textArtifact(ocr ? "ocr.txt" : "transcript.txt", text);
            JSONObject reference = new JSONObject();
            for (String key : new String[]{"artifactId", "runId", "fileName", "mediaType", "size", "sha256", "expiresAt"}) {
                reference.put(key, file.get(key));
            }
            result.put("artifact", reference);
        }
        return result;
    }

    /**
     * 由 Unit 负载构造识别结果，并校验文本长度与音频元数据。
     *
     * @param ocr     是否为 OCR。
     * @param id      输入文件 ID。
     * @param payload Unit 返回负载。
     * @return 识别结果。
     */
    static JSONObject recognitionResult(boolean ocr, String id, JSONObject payload) {
        String field = ocr ? "response" : "text";
        if (payload == null || !(payload.opt(field) instanceof String)) {
            throw new StoreException("CAPABILITY_RESULT_INVALID");
        }
        String text = payload.getString(field);
        if (text.indexOf(0) >= 0 || RuntimeSupport.bytes(new JSONObject().put("text", text)).length > 131072) {
            throw new StoreException("CAPABILITY_RESULT_INVALID");
        }
        int length = text.codePointCount(0, text.length());
        JSONObject result = new JSONObject()
                .put("inputId", id)
                .put("unit", ocr ? "OmniPerception" : "Speech")
                .put("text", text.substring(0, text.offsetByCodePoints(0, Math.min(length, 1024))))
                .put("truncated", length > 1024);
        if (!ocr) {
            double duration = payload.optDouble("audioDurationSeconds", Double.NaN);
            Object language = payload.opt("lang");
            if (!Double.isFinite(duration) || duration <= 0 || duration > 300
                    || !(language instanceof String) || ((String) language).length() > 24) {
                throw new StoreException("CAPABILITY_RESULT_INVALID");
            }
            result.put("language", language).put("durationSeconds", duration);
        }
        return result;
    }

    /**
     * 由图片像素与识别区域构造 OCR 请求选项。
     *
     * @param bytes 图片字节。
     * @param type  声明的媒体类型。
     * @param args  调用参数。
     * @return OCR 请求选项。
     * @throws Exception 图片解码失败。
     */
    static JSONObject ocrOption(byte[] bytes, String type, JSONObject args) throws Exception {
        Dimension image = CapabilityInvokeProvider.inspectDimensions(bytes, type, 16000000);
        int w = image.width;
        int h = image.height;
        JSONObject region = args.optJSONObject("region");
        int x1 = region == null ? 0 : region.getInt("x1");
        int y1 = region == null ? 0 : region.getInt("y1");
        int x2 = region == null ? w : region.getInt("x2");
        int y2 = region == null ? h : region.getInt("y2");
        if (x1 < 0 || y1 < 0 || x2 > w || y2 > h || x1 >= x2 || y1 >= y2) {
            throw new StoreException("OCR_REGION_INVALID");
        }
        JSONObject parameters = new JSONObject()
                .put("bboxList", new JSONArray()
                        .put(new JSONArray().put(x1).put(y1).put(x2).put(y2)))
                .put("targetRegionIndexes", new JSONArray().put(0));
        if (args.has("language")) {
            parameters.put("lang", args.getString("language"));
        }
        return new JSONObject()
                .put("operation", "analyze")
                .put("task", "region_ocr")
                .put("parameters", parameters);
    }
}
