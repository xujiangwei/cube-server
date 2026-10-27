/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.file.VideoOperation;
import cube.util.FileType;
import cube.util.TimeDuration;
import org.json.JSONObject;

/**
 * 视频快照操作。
 */
public class SnapshotOperation extends VideoOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "Snapshot";

    /** 截图的时间点，从视频起始位置算起。 */
    public TimeDuration offset;

    /** 时长。 */
    public TimeDuration duration;

    /** 比率。 */
    public double rate;

    /** 输出类型。 */
    public FileType outputType;

    /** 是否把截取的图片打包为 ZIP ，默认启用。 */
    public boolean packToZip = true;

//    private List<TimeOffset> timeOffsets;

    public SnapshotOperation() {
        super();
        this.offset = new TimeDuration(0, 0, 0);
        this.duration = new TimeDuration(0, 0, 0,0);
        this.rate = 1;
        this.outputType = FileType.JPEG;
    }

    public SnapshotOperation(JSONObject json) {
        super();
        this.offset = new TimeDuration(json.getJSONObject("offset"));
        this.duration = new TimeDuration(json.getJSONObject("duration"));
        this.rate = json.getDouble("rate");
        this.outputType = FileType.matchExtension(json.getString("outputType"));
        this.packToZip = json.getBoolean("packToZip");

    }

    /**
     * 获取操作名称。
     *
     * @return 固定返回 {@link #Operation} 。
     */
    @Override
    public String getOperation() {
        return SnapshotOperation.Operation;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("offset", this.offset.toJSON());
        json.put("duration", this.duration.toJSON());
        json.put("rate", this.rate);
        json.put("outputType", this.outputType.getPreferredExtension());
        json.put("packToZip", this.packToZip);
        return json;
    }
}
