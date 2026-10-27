/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.attachment;

import cube.common.entity.FileLabel;

/**
 * 文件附件。
 *
 * <p>以文件形式呈现的附件，携带文件标签供客户端下载。</p>
 */
public class FileAttachment extends Attachment {

    /** 附件类型标识。 */
    public final static String TYPE = "File";

    /** 文件标签。 */
    public FileLabel fileLabel;

    public FileAttachment(FileLabel fileLabel) {
        super(TYPE);
        this.fileLabel = fileLabel;
    }
}
