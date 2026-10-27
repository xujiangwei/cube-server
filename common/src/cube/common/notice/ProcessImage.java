/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import cube.common.action.FileProcessorAction;
import cube.common.entity.FileLabel;
import cube.file.ImageOperation;

/**
 * 处理图像数据。
 */
public class ProcessImage extends NoticeData {

    /** 通知动作名：处理图像。 */
    public final static String ACTION = FileProcessorAction.Image.name;

    /** 请求参数：域名。 */
    public final static String DOMAIN = "domain";

    /** 请求参数：源文件码。 */
    public final static String FILE_CODE = "fileCode";

    /** 请求参数：处理参数。 */
    public final static String PARAMETER = "parameter";

    public ProcessImage(FileLabel fileLabel, ImageOperation operation) {
        super(ACTION);
        this.put(DOMAIN, fileLabel.getDomain().getName());
        this.put(FILE_CODE, fileLabel.getFileCode());
        this.put(PARAMETER, operation.toJSON());
    }

    public ProcessImage(String domain, String fileCode, ImageOperation operation) {
        super(ACTION);
        this.put(DOMAIN, domain);
        this.put(FILE_CODE, fileCode);
        this.put(PARAMETER, operation.toJSON());
    }
}
