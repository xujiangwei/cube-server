/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.util.TextUtils;
import org.json.JSONObject;

public class Answer {

    /** 序列号。 */
    public final int sn;

    /** 码。 */
    public final String code;

    /** 内容。 */
    public final String content;

    /** 选中。 */
    public boolean chosen = false;

    public Answer(int sn, JSONObject structure) {
        this.sn = sn;
        this.code = structure.getString("code");
        this.content = structure.getString("content");
        if (structure.has("chosen")) {
            this.chosen = structure.getBoolean("chosen");
        }
    }

    public Answer(JSONObject json) {
        this.sn = json.getInt("sn");
        this.code = json.getString("code");
        this.content = json.getString("content");
        if (json.has("chosen")) {
            this.chosen = json.getBoolean("chosen");
        }
    }

    /**
     * 判断是否数字码。
     *
     * @return 数字码。
     */
    public boolean isNumericCode() {
        return TextUtils.isNumeric(this.code);
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("code", this.code);
        json.put("content", this.content);
        json.put("chosen", this.chosen);
        return json;
    }
}
