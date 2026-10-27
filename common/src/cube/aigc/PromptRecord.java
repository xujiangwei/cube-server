/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cell.util.Utils;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 提示词记录。
 */
public class PromptRecord implements JSONable {

    /** 提示词 ID 。 */
    public final long id;

    /** 提示词标题。 */
    public final String title;

    /** 提示词正文。 */
    public final String content;

    /** 触发动作名，点击提示词时下发。 */
    public final String act;

    /** 是否只读；只读项不允许用户编辑或删除。 */
    public final boolean readonly;

    public PromptRecord(long id, String title, String content, String act, boolean readonly) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.act = act;
        this.readonly = readonly;
    }

    public PromptRecord(JSONObject json) {
        this.id = json.has("id") ? json.getLong("id") : Utils.generateSerialNumber();
        this.title = json.getString("title");
        this.content = json.getString("content");
        this.act = json.getString("act");
        this.readonly = json.getBoolean("readonly");
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
        json.put("title", this.title);
        json.put("content", this.content);
        json.put("act", this.act);
        json.put("readonly", this.readonly);
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
