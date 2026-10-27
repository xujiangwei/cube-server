/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.guidance;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 答案分组。
 *
 * <p>同一问题下的并行回答单元：每个分组对应一个子话题，各自记录状态与答案，
 * 全部分组完成才算该问题回答完毕。</p>
 */
public class AnswerGroup {

    /** 状态：未开始。 */
    public final static int STATE_NONE = 0;

    /** 状态：回答中。 */
    public final static int STATE_ANSWERING = 1;

    /** 状态：已回答。 */
    public final static int STATE_ANSWERED = 2;

    /** 当前状态，取值见 {@code STATE_*} 。 */
    public int state = STATE_NONE;

    /** 分组名。 */
    public final String group;

    /** 分组说明文本。 */
    public final String content;

    /** 分组内的候选答案。 */
    public final List<Answer> answers;

    public AnswerGroup(JSONObject json) {
        this.group = json.getString("group");
        this.content = json.getString("content");
        this.answers = new ArrayList<>();
        JSONArray array = json.getJSONArray("answers");
        for (int i = 0; i < array.length(); ++i) {
            this.answers.add(new Answer(array.getJSONObject(i)));
        }
    }

    /**
     * 按编码获取候选答案。
     *
     * @param code 答案编码。
     * @return 答案；无匹配返回 {@code null} 。
     */
    public Answer getAnswer(String code) {
        for (Answer answer : this.answers) {
            if (answer.code.equalsIgnoreCase(code)) {
                return answer;
            }
        }
        return null;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("group", this.group);
        json.put("content", this.content);
        JSONArray array = new JSONArray();
        for (Answer answer : this.answers) {
            array.put(answer.toJSON());
        }
        json.put("answers", array);
        return json;
    }
}
