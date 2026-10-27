/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.benchmark;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 反应时间。
 */
public class ResponseTime implements JSONable {

    /** 耗时标记，通常为动作名。 */
    public final String mark;

    /** 开始时间戳，单位毫秒。 */
    public long beginning = 0;

    /** 结束时间戳，单位毫秒。 */
    public long ending = 0;

    public ResponseTime(String mark) {
        this.mark = mark;
    }

    public ResponseTime(JSONObject json) {
        this.mark = json.getString("mark");
        this.beginning = json.getLong("beginning");
        this.ending = json.getLong("ending");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("mark", this.mark);
        json.put("beginning", this.beginning);
        json.put("ending", this.ending);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return toJSON();
    }
}
