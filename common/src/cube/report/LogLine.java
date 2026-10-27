/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.report;

import cell.util.log.LogLevel;
import cell.util.log.Logger;
import cube.common.JSONable;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 日志行。
 */
public class LogLine implements JSONable {

    private final static SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    /** 日志级别。 */
    public int level;

    /** 日志标签，通常为来源类名。 */
    public String tag;

    /** 日志正文。 */
    public String text;

    /** 日志时间戳，单位毫秒。 */
    public long time;

    public LogLine(int level, String tag, String text, long time) {
        this.level = level;
        this.tag = tag;
        this.text = text;
        this.time = time;
    }

    public LogLine(JSONObject json) {
        try {
            this.level = json.getInt("level");
            this.tag = json.getString("tag");
            this.text = json.getString("text");
            this.time = json.getLong("time");
        } catch (JSONException e) {
            Logger.e(LogLine.class, "#LogLine - JSON error", e);
        }
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(TIME_FORMAT.format(new Date(this.time)));
        buf.append(" [");
        buf.append(LogLevel.parse(this.level).name());
        buf.append("] ");
        buf.append(this.tag);
        buf.append(" ");
        buf.append(this.text);
        return buf.toString();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        try {
            json.put("level", this.level);
            json.put("tag", this.tag);
            json.put("text", this.text);
            json.put("time", this.time);
        } catch (JSONException e) {
            Logger.e(LogLine.class, "#toJSON - JSON error", e);
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
