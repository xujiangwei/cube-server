/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import org.json.JSONObject;

/**
 * 受信的文件处理提供方扩展，由管理员随部署安装；业务包脚本拿不到它的解释器与依赖库。
 *
 * <p>本扩展在普通工具调用之上增加受控的文件交换能力：宿主据此校验运行环境、限定输入媒体类型，
 * 并以流式分块方式读取上传输入、写出产物。所有文件读写都经宿主账本授权与落库，
 * 提供方只声明参数与元数据，不直接触碰文件路径。</p>
 */
public interface FileProcessingProvider extends QueryProvider {

    /**
     * 返回本提供方所需的文件处理环境声明，用于校验部署环境是否可用。
     *
     * @return 含 {@code environmentId}、{@code environmentDigest} 与 {@code operation} 的环境声明。
     */
    JSONObject fileProcessing();

    /**
     * 从工具参数中解析出输入文件 id，适用于单输入场景。
     *
     * @param arguments 已校验的工具参数。
     * @return 输入文件 id。
     */
    String inputId(JSONObject arguments);

    /**
     * 返回本提供方接受的全部输入媒体类型。
     *
     * @return 输入媒体类型集合。
     */
    java.util.Set<String> inputMediaTypes();

    /**
     * 返回本次调用涉及的全部输入文件 id，默认只取 {@link #inputId(JSONObject)} 一项。
     *
     * <p>需要多输入的场景（如合并）由实现方覆写本方法。id 须为合法标识且不得重复。</p>
     *
     * @param arguments 已校验的工具参数。
     * @return 输入文件 id 列表。
     */
    default java.util.List<String> inputIds(JSONObject arguments) {
        return java.util.Collections.singletonList(inputId(arguments));
    }

    /**
     * 返回指定输入位置允许读取的媒体类型，默认与提供方声明的全部输入类型一致。
     *
     * @param arguments 已校验的工具参数。
     * @param index     输入在 {@link #inputIds(JSONObject)} 中的位置。
     * @return 该输入允许的媒体类型集合。
     */
    default java.util.Set<String> inputMediaTypes(JSONObject arguments, int index) {
        return inputMediaTypes();
    }

    /**
     * 构造产物元数据，用于开始一次受控的文件写出。
     *
     * <p>实现方须在此校验参数与产物大小，返回的元数据由宿主落库后才允许写入字节。</p>
     *
     * @param arguments 已校验的工具参数。
     * @param size      产物字节数。
     * @param sha256    产物内容摘要。
     * @return 含文件名、媒体类型、大小与摘要的产物元数据。
     */
    JSONObject outputMetadata(JSONObject arguments, long size, String sha256);
}
