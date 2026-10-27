/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import org.json.JSONObject;

/**
 * 进入静默状态。
 */
public class SilenceSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "Silence";

    private long duration;

    public SilenceSignal(long duration) {
        super(NAME);
        this.duration = duration;
    }

    public SilenceSignal(JSONObject json) {
        super(json);
        this.duration = json.getLong("duration");
    }

    /**
     * 获取时长。
     *
     * @return 时长。
     */
    public long getDuration() {
        return this.duration;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("duration", this.duration);
        return json;
    }
}
