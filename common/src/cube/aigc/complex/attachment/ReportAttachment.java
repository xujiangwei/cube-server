/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.attachment;

import cube.common.entity.FileLabel;
import org.json.JSONObject;

/**
 * 报告附件。
 *
 * <p>以报告形式呈现的附件，内容为结构化报告数据。</p>
 */
public class ReportAttachment extends Attachment {

    /** 附件类型标识。 */
    public final static String TYPE = "Report";

    private FileLabel fileLabel;

    public ReportAttachment(long sn, FileLabel fileLabel) {
        super(TYPE, sn);
        this.fileLabel = fileLabel;
    }

    public ReportAttachment(JSONObject json) {
        super(json);
        if (json.has("fileLabel")) {
            this.fileLabel = new FileLabel(json.getJSONObject("fileLabel"));
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        if (null != this.fileLabel) {
            json.put("fileLabel", this.fileLabel.toCompactJSON());
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
