/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class QuestionSection {

    /** 标题。 */
    public final String title;

    /** 内容。 */
    public final String content;

    private final List<Question> questions;

    public QuestionSection(JSONObject structure) {
        this.title = structure.has("title") ? structure.getString("title") : "";
        this.content = structure.has("content") ? structure.getString("content") : "";
        this.questions = new ArrayList<>();
        JSONArray array = structure.getJSONArray("questions");
        for (int i = 0; i < array.length(); ++i) {
            this.questions.add(new Question(array.getJSONObject(i)));
        }
    }

    /**
     * 获取问题列表。
     *
     * @return 问题列表。
     */
    public List<Question> getQuestions() {
        List<Question> list = new ArrayList<>();
        for (Question question : this.questions) {
            if (question.hidden) {
                continue;
            }
            list.add(question);
        }
        return list;
    }

    /**
     * 统计问题列表。
     *
     * @return 数量。
     */
    public int numQuestions() {
        int num = 0;
        for (Question question : this.questions) {
            if (question.hidden) {
                continue;
            }
            ++num;
        }
        return num;
    }

    /**
     * 获取隐藏题目。
     *
     * @return 隐藏题目。
     */
    public List<Question> getHiddenQuestions() {
        List<Question> list = new ArrayList<>();
        for (Question question : this.questions) {
            if (question.hidden) {
                list.add(question);
            }
        }
        return list;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("title", this.title);
        json.put("content", this.content);

        JSONArray array = new JSONArray();
        for (Question question : this.questions) {
            array.put(question.toJSON());
        }
        json.put("questions", array);

        return json;
    }
}
