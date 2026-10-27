/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import org.json.JSONObject;

/**
 * 获取文件标签。
 */
public class GetFileLabelSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "GetFileLabel";

    private String fileCode;

    public GetFileLabelSignal(String channelCode, String fileCode) {
        super(NAME);
        setCode(channelCode);
        this.fileCode = fileCode;
    }

    public GetFileLabelSignal(JSONObject json) {
        super(json);
        this.fileCode = json.getString("fileCode");
    }

    /**
     * 获取文件码。
     *
     * @return 文件码。
     */
    public String getFileCode() {
        return this.fileCode;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("fileCode", this.fileCode);
        return json;
    }
}
