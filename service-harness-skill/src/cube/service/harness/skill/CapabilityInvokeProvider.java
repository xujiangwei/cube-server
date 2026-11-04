/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.execution.QueryProvider;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 能力调用技能：调用既有 Unit 完成真实图像分析。
 *
 * <p>不引入新的视觉模型，Unit 返回的物理回执会被持久化；超时或取消只说明
 * 「物理结果未知」，不会自动重发。</p>
 */
public final class CapabilityInvokeProvider extends AbstractProvider {
    /**
     * 外部数据源访问器，用于按需拉取原始图片。
     */
    private final DataNetwork network;

    /**
     * 允许调用的 Unit 名单。
     */
    private final Set<String> allowed;

    /**
     * 单次调用的超时时间，单位毫秒。
     */
    private final int timeout;

    /**
     * 图片允许的最大像素数。
     */
    private final int maxPixels;

    /**
     * 构造函数。
     *
     * @param p 技能私有配置。
     */
    public CapabilityInvokeProvider(Properties p) {
        super("capability", "common.capability.invoke", "capability.invoke", p);
        network = new DataNetwork(p);
        allowed = new HashSet<>(Arrays.asList(p.getProperty("capability.allowed.units", "").split(",")));
        timeout = number(p, "capability.timeout.millis", 20000, 100, 20000);
        maxPixels = number(p, "image.max.pixels", 4000000, 1, 16000000);
    }

    /**
     * 能力调用必须独立执行，不能与其它工具共享一次受理。
     *
     * @return 固定返回 true 。
     */
    public boolean independentExecution() {
        return true;
    }

    /**
     * 返回技能需要读取的配置文件清单。
     *
     * @return 返回配置文件名集合。
     */
    public Set<String> configurationFiles() {
        return Collections.singleton("analysis-profiles.json");
    }

    /**
     * 校验技能配置。
     *
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    public void validateConfiguration(String path, JSONObject config) {
        AnalysisProfiles.validate(config);
    }

    /**
     * 受理前检查：档案引用的 Unit 必须在允许名单内，且当前有就绪且有余量的实例。
     *
     * @param lookup Unit 运行状态查询入口。
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    public void preflightConfiguration(QueryProvider.ProviderInvocationLookup lookup, String path, JSONObject config) {
        validateConfiguration(path, config);
        JSONArray units = lookup.mediaUnits();
        JSONArray profiles = config.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            String name = profiles.getJSONObject(i).getString("unit");
            boolean ready = false;
            for (int j = 0; j < units.length(); j++) {
                if (units.getJSONObject(j).getString("unit").equals(name)
                        && units.getJSONObject(j).getBoolean("ready")
                        && units.getJSONObject(j).getInt("capacity") > 0) {
                    ready = true;
                }
            }
            if (!allowed.contains(name) || !ready) {
                throw new StoreException("CAPABILITY_NOT_READY");
            }
        }
    }

    /**
     * 对账物理执行状态，用于重开后的未知结果确认。
     *
     * @param c Broker 提供的可信调用上下文。
     * @return 返回 Unit 侧是否已给出物理完成回执。
     */
    public boolean reconcile(ProviderInvocation c) {
        return c.mediaFinished();
    }

    /**
     * 执行能力调用：取图、校验、预留 Unit、等待物理回执并产出溯源产物。
     *
     * @param c    Broker 提供的可信调用上下文。
     * @param args 调用参数。
     * @return 返回分析结果，含 profileId 、answer 与溯源信息。
     * @throws Exception 拉取图片或等待回执过程中的异常。
     */
    protected JSONObject execute(ProviderInvocation c, JSONObject args) throws Exception {
        String skill = args.getString("skill");
        JSONObject config = c.resource(skill, "analysis-profiles.json");
        AnalysisProfiles.validate(config);
        JSONObject profile = ConnectorConfig.select(config, "profiles", args.getString("profileId"));
        if (!allowed.contains(profile.getString("unit"))) {
            throw new StoreException("CAPABILITY_NOT_READY");
        }
        JSONObject media = c.confirmed("media", args.getString("mediaRef"));
        if (!media.getString("skill").equals(skill)) {
            throw new StoreException("MEDIA_REF_FORBIDDEN");
        }
        if (!profile.getJSONArray("mediaTypes").toList().contains(media.getString("mediaType"))) {
            throw new StoreException("MEDIA_TYPE_UNSUPPORTED");
        }
        JSONObject connectors = c.resource(skill, "connectors.json");
        ConnectorConfig.validate(connectors);
        JSONObject connector = ConnectorConfig.select(connectors, "connectors", media.getString("connectorId"));
        byte[] bytes;
        if (media.has("inputId")) {
            bytes = c.input(media.getString("inputId"), 1048576, Collections.singleton("application/octet-stream"));
        } else {
            if (!media.getString("sourceRef").equals(connector.getString("sourceRef"))) {
                throw new StoreException("MEDIA_REF_FORBIDDEN");
            }
            NetClient.Response raw = network.get(c, media.getString("sourceRef"), connector.getJSONObject("media").getJSONObject("request"),
                    new JSONObject().put("id", media.getString("mediaId")).put("version", media.getString("mediaVersion")),
                    "media", new JSONObject(), 1048576);
            if (!raw.media().equals(media.getString("mediaType"))) {
                throw new StoreException("MEDIA_VERSION_CHANGED");
            }
            bytes = raw.body;
        }
        inspect(bytes, media.getString("mediaType"), maxPixels);
        String sha = RuntimeSupport.sha(bytes);
        c.remember("media-bytes", args.getString("mediaRef"), new JSONObject().put("sha256", sha));
        JSONObject request = new JSONObject().put("unit", profile.getString("unit")).put("adapter", profile.getString("adapter"))
                .put("option", ConnectorConfig.arguments(profile.getString("optionsJson"))).put("content", args.getString("content"))
                .put("tenant", c.tenant).put("subject", c.subject).put("runId", c.runId).put("sha256", sha).put("mediaType", media.getString("mediaType"))
                .put("deadlineAt", System.currentTimeMillis() + timeout);
        JSONObject execution = c.reserveMedia(profile.getString("unit"), request);
        CompletableFuture<JSONObject> response = new CompletableFuture<>();
        c.invokeMedia(execution, bytes, response::complete);
        // 超时抛出的是非 StoreException ：Broker 记为物理结果未知，且不自动重发。
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        JSONObject receipt = null;
        while (receipt == null) {
            if (c.cancelled()) {
                c.cancelMedia();
                throw new IOException("MEDIA_EXECUTION_CANCELLED_WITHOUT_PHYSICAL_RECEIPT");
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                c.cancelMedia();
                throw new TimeoutException("MEDIA_EXECUTION_UNKNOWN");
            }
            try {
                receipt = response.get(Math.min(100, remaining), TimeUnit.MILLISECONDS);
            } catch (TimeoutException pending) {
                // 继续检查取消标记与同一个截止时间。
            }
        }
        if (!receipt.optBoolean("success")) {
            throw new StoreException("CAPABILITY_FAILED");
        }
        JSONObject payload = receipt.getJSONObject("payload");
        JSONArray warnings = new JSONArray().put("OBSERVATION_ONLY_NO_BUSINESS_ABNORMALITY_DECISION");
        if (media.getString("timeTrust").equals("provider-declared")) {
            warnings.put("CAPTURE_TIME_PROVIDER_DECLARED");
        }
        JSONObject out = new JSONObject().put("profileId", args.getString("profileId")).put("mediaRef", args.getString("mediaRef"))
                .put("unit", profile.getString("unit")).put("answer", payload.optString("response", ""))
                .put("payloadJson", payload.optJSONObject("resultPayload") == null ? "{}" : payload.getJSONObject("resultPayload").toString()).put("warnings", warnings);
        validateResult(args, out);
        JSONObject provenance = new JSONObject().put("schemaVersion", 1).put("kind", "media-source")
                .put("mediaRef", args.getString("mediaRef")).put("mediaId", media.getString("mediaId")).put("mediaVersion", media.getString("mediaVersion"))
                .put("mediaType", media.getString("mediaType")).put("sha256", sha).put("source", media.getJSONObject("record"))
                .put("captureTimeTrust", media.getString("timeTrust")).put("profileId", args.getString("profileId"))
                .put("requestId", execution.getString("requestId")).put("instanceId", execution.getString("instanceId"))
                .put("analysisResultSha256", RuntimeSupport.sha(RuntimeSupport.bytes(out)));
        if (media.has("sourceRef")) {
            provenance.put("sourceRef", media.getString("sourceRef"));
        }
        if (media.has("inputId")) {
            provenance.put("inputId", media.getString("inputId"));
        }
        c.textArtifact("media-source.json", provenance.toString());
        return out;
    }

    /**
     * 校验图片是否可解码、格式与声明一致，且像素数没有越界。
     *
     * @param bytes     图片字节。
     * @param mediaType 声明的媒体类型。
     * @param maxPixels 允许的最大像素数。
     * @throws Exception 读取或解码图片过程中的异常。
     */
    static void inspect(byte[] bytes, String mediaType, int maxPixels) throws Exception {
        if (bytes.length < 8 || bytes.length > 1048576) {
            throw new StoreException("MEDIA_IMAGE_INVALID");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new StoreException("MEDIA_IMAGE_INVALID");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(mediaType.equals("image/jpeg") && format.equals("jpeg"))
                        && !(mediaType.equals("image/png") && format.equals("png"))) {
                    throw new StoreException("MEDIA_IMAGE_INVALID");
                }
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * height > maxPixels) {
                    throw new StoreException("MEDIA_PIXEL_LIMIT");
                }
                if (reader.read(0) == null) {
                    throw new StoreException("MEDIA_IMAGE_INVALID");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException invalid) {
            throw new StoreException("MEDIA_IMAGE_INVALID");
        }
    }

    /**
     * 释放技能持有的网络资源。
     */
    public void close() {
        network.close();
    }
}
