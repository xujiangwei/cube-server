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
 * 引导章节。
 *
 * <p>流程中的一个逻辑段落：包含若干问题节点与本章的评估规则，
 * 章节结束后产出 {@link EvaluationResult} 。</p>
 */
public class GuidanceSection {

    /** 章节名称。 */
    public final String name;

    private final List<Question> questionList;

    /** 本章的评估规则描述。 */
    public final String evaluation;

    /**
     * 中断提示。
     */
    public final String interruption;

    /** 进入本章的时间戳，单位毫秒。 */
    public long startTimestamp;

    /** 离开本章的时间戳，单位毫秒。 */
    public long endTimestamp;

    /** 本章的评估结果。 */
    public EvaluationResult evaluationResult;

    public GuidanceSection(String path, JSONObject json) {
        this.name = json.getString("name");
        this.questionList = new ArrayList<>();
        JSONArray array = json.getJSONArray("questions");
        for (int i = 0; i < array.length(); ++i) {
            this.questionList.add(new Question(path, array.getJSONObject(i)));
        }
        this.evaluation = json.getString("evaluation");
        if (json.has("interruption")) {
            this.interruption = json.getString("interruption");
        }
        else {
            this.interruption = "";
        }
    }

    /**
     * 获取本章全部问题。
     *
     * @return 问题列表；无问题时为空列表。
     */
    public List<Question> getAllQuestions() {
        return this.questionList;
    }

    /**
     * 按序号获取问题。
     *
     * @param index 序号，从 0 开始。
     * @return 问题；越界返回 {@code null} 。
     */
    public Question getQuestion(int index) {
        return this.questionList.get(index);
    }

    /**
     * 按编号获取问题。
     *
     * @param sn 问题编号。
     * @return 问题；不存在返回 {@code null} 。
     */
    public Question getQuestion(String sn) {
        for (Question question : this.questionList) {
            if (question.sn.equals(sn)) {
                return question;
            }
        }
        return null;
    }

    /**
     * 获取中断提示。
     *
     * @return 提示文本；无需中断返回 {@code null} 。
     */
    public String getInterruption() {
        return this.interruption;
    }

    /**
     * 判断本章是否已完成。
     *
     * @return 全部问题已回答返回 {@code true} 。
     */
    public boolean hasCompleted() {
        int count = 0;
        for (Question question : this.questionList) {
            if (question.isAnswerGroup() && question.hasAnswerGroupCompleted()) {
                ++count;
            }
            else if (question.hasAnswer()) {
                ++count;
            }
        }
        return (count == this.questionList.size());
    }

    /**
     * 是否终止整个向导流。
     * @return 满足条件返回 {@code true} 。
     */
    public boolean hasTerminated() {
        return (null != this.evaluationResult && this.evaluationResult.hasTerminated());
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);

        JSONArray array = new JSONArray();
        for (Question question : this.questionList) {
            array.put(question.toJSON());
        }
        json.put("questions", array);

        json.put("evaluation", this.evaluation);
        if (null != this.interruption) {
            json.put("interruption", this.interruption);
        }

        return json;
    }
}
