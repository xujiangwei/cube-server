/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.app;

import cell.util.Utils;
import cube.common.JSONable;
import cube.util.TimeUtils;
import org.json.JSONObject;

import java.util.Date;

/**
 * 应用通知。
 *
 * <p>下发到客户端的公告或弹窗：含标题、正文、生效状态与时间。
 * 只有 {@code state} 为启用时才会被客户端展示。</p>
 */
public class Notification implements JSONable {

    /** 通知类型：普通消息。 */
    public final static String TYPE_NORMAL = "normal";

    /** 通知类型：弹窗。 */
    public final static String TYPE_POPUP = "popup";

    /** 状态：已启用。 */
    public final static int STATE_ENABLED = 1;

    /** 状态：已停用。 */
    public final static int STATE_DISABLED = 0;

    /** 通知 ID 。 */
    public long id;

    /** 通知类型，取值见 {@code TYPE_*} 。 */
    public String type;

    /** 状态，取值见 {@code STATE_*} 。 */
    public int state;

    /** 标题。 */
    public String title;

    /** 正文内容。 */
    public String content;

    /** 发布日期文本。 */
    public String date;

    /** 发布时间戳，单位毫秒。 */
    public long timestamp;

    public Notification(String title, String content) {
        this.id = Utils.generateSerialNumber();
        this.type = TYPE_NORMAL;
        this.state = STATE_ENABLED;
        this.title = title;
        this.content = content;
        this.date = Utils.gsDateFormat.format(new Date(System.currentTimeMillis()));
        this.timestamp = System.currentTimeMillis();
    }

    public Notification(long id, String type, int state, String title, String content, String date) {
        this.id = id;
        this.type = type;
        this.state = state;
        this.title = title;
        this.content = content;
        this.date = date;
        this.timestamp = TimeUtils.unformatDate(date);
    }

    public Notification(JSONObject json) {
        this.id = json.getLong("id");
        this.type = json.getString("type");
        this.state = json.getInt("state");
        this.title = json.getString("title");
        this.content = json.getString("content");
        this.date = json.getString("date");
        this.timestamp = json.getLong("timestamp");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id);
        json.put("type", this.type);
        json.put("state", this.state);
        json.put("title", this.title);
        json.put("content", this.content);
        json.put("date", this.date);
        json.put("timestamp", this.timestamp);
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
