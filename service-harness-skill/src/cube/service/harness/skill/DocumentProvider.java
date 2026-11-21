/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.FileProcessingProvider;
import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * PDF 编辑由已安装的 Python worker 负责；本 provider 只做文件与结果授权。
 */
public final class DocumentProvider extends AbstractProvider implements FileProcessingProvider {
    /**
     * 已安装处理环境的描述。
     */
    private final JSONObject processing;

    /**
     * 构造文档处理 provider 并校验已安装环境清单。
     *
     * @param config 技能私有配置。
     * @throws Exception 环境清单读取或解析失败。
     */
    public DocumentProvider(Properties config) throws Exception {
        super("document", "common.document.watermark", "document.watermark", config);
        JSONObject environment = StrictJson.object(Files.readAllBytes(Paths.get(config.getProperty("environment.manifest"))));
        if (environment.getInt("schemaVersion") != 1 || !environment.getString("operation").equals("pdf.watermark.v1")) {
            throw new StoreException("FILE_ENVIRONMENT_INVALID");
        }
        processing = new JSONObject()
                .put("environmentId", RuntimeSupport.id(environment.getString("id")))
                .put("environmentDigest", JdbcRunStore.hash(environment))
                .put("operation", "pdf.watermark.v1");
    }

    /**
     * 返回副作用类型。
     *
     * @return 产物创建。
     */
    public String effect() {
        return "artifact-create";
    }

    /**
     * 返回 Broker 等待物理处理的最长毫秒数。
     *
     * @return 等待上限。
     */
    public int runnerWaitMillis() {
        return 120000;
    }

    /**
     * 返回文件处理环境描述。
     *
     * @return 环境描述。
     */
    public JSONObject fileProcessing() {
        return new JSONObject(processing.toString());
    }

    /**
     * 从调用参数中取输入文件引用。
     *
     * @param arguments 调用参数。
     * @return 输入文件 ID。
     */
    public String inputId(JSONObject arguments) {
        return arguments.getString("inputId");
    }

    /**
     * 返回接受的输入媒体类型。
     *
     * @return 媒体类型集合。
     */
    public Set<String> inputMediaTypes() {
        return Collections.singleton("application/pdf");
    }

    /**
     * 生成输出文件元数据。
     *
     * @param arguments 调用参数。
     * @param size      输出字节数。
     * @param sha256    输出摘要。
     * @return 输出元数据。
     */
    public JSONObject outputMetadata(JSONObject arguments, long size, String sha256) {
        validate(arguments);
        if (size < 1 || size > 10485760) {
            throw new StoreException("FILE_TOO_LARGE");
        }
        return new JSONObject()
                .put("fileName", arguments.optString("fileName", "watermarked.pdf"))
                .put("mediaType", "application/pdf")
                .put("size", size)
                .put("sha256", RuntimeSupport.digest(sha256));
    }

    /**
     * 校验水印文本、文件名与页码范围。
     *
     * @param arguments 调用参数。
     */
    public void validate(JSONObject arguments) {
        super.validate(arguments);
        if (arguments.getString("text").trim().isEmpty() || arguments.getString("text").matches("(?s).*[\\p{Cntrl}].*")) {
            throw new StoreException("WATERMARK_TEXT_INVALID");
        }
        String name = arguments.optString("fileName", "watermarked.pdf");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf") || !name.matches("[^\\\\/\\p{Cntrl}]{5,256}")) {
            throw new StoreException("INVALID_FILE_METADATA");
        }
        if (arguments.has("pages")) {
            Set<Integer> pages = new HashSet<>();
            for (int i = 0; i < arguments.getJSONArray("pages").length(); i++) {
                if (!pages.add(arguments.getJSONArray("pages").getInt(i))) {
                    throw new StoreException("PDF_PAGE_RANGE_INVALID");
                }
            }
        }
    }

    /**
     * 返回产物引用与操作类型。
     *
     * @param context   调用上下文。
     * @param arguments 调用参数。
     * @return 执行结果。
     */
    protected JSONObject execute(ProviderInvocation context, JSONObject arguments) {
        return new JSONObject()
                .put("artifact", context.binaryArtifact())
                .put("operation", "pdf.watermark.v1")
                .put("inputId", inputId(arguments));
    }
}
