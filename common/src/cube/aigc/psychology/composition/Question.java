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

public class Question {

    /**
     * 单选。
     */
    public final static String CHOICE_SINGLE = "single";

    /**
     * 多选。
     */
    public final static String CHOICE_MULTIPLE = "multiple";

    /**
     * 叙述。
     */
    public final static String CHOICE_DESCRIPTIVE = "descriptive";

    /** 序列号。 */
    public final int sn;

    /** 隐藏。 */
    public final boolean hidden;

    /** 内容。 */
    public final String content;

    /** 提示词。 */
    public final String prompt;

    /** 推断。 */
    public final String inference;

    /** 回答列表。 */
    public final List<Answer> answers = new ArrayList<>();

    /** 选项。 */
    public final String choice;

    /** 问题内容。 */
    public String questionContent;

    /** 回答内容。 */
    public String answerContent;

    /** 推断结果。 */
    public String inferenceResult;

    public Question(JSONObject structure) {
        this.sn = structure.getInt("sn");
        this.hidden = structure.has("hidden") && structure.getBoolean("hidden");
        this.content = structure.getString("content");
        this.prompt = structure.has("prompt") ? structure.getString("prompt") : "";
        this.inference = structure.has("inference") ? structure.getString("inference") : "";
        this.inferenceResult = structure.has("inferenceResult") ? structure.getString("inferenceResult") : "";
        this.choice = structure.has("choice") ? structure.getString("choice") : CHOICE_SINGLE;
        this.questionContent = structure.has("questionContent") ? structure.getString("questionContent") : this.content;
        this.answerContent = structure.has("answerContent") ? structure.getString("answerContent") : "";
        JSONArray array = structure.getJSONArray("answers");
        for (int i = 0; i < array.length(); ++i) {
            Answer answer = new Answer(this.sn, array.getJSONObject(i));
            this.answers.add(answer);
        }
    }

    /**
     * 判断是否单选。
     *
     * @return 单选。
     */
    public boolean isSingleChoice() {
        return this.choice.equalsIgnoreCase(CHOICE_SINGLE);
    }

    /**
     * 判断是否多选。
     *
     * @return 多选。
     */
    public boolean isMultipleChoice() {
        return this.choice.equalsIgnoreCase(CHOICE_MULTIPLE);
    }

    /**
     * 判断是否描述性。
     *
     * @return 描述性。
     */
    public boolean isDescriptive() {
        return this.choice.equalsIgnoreCase(CHOICE_DESCRIPTIVE);
    }

    /**
     * 提交作答内容。
     *
     * @param content 作答文本。
     */
    public void submitAnswerContent(String content) {
        this.answerContent = content;
    }

    /**
     * 获取回答内容。
     *
     * @return 回答内容。
     */
    public String getAnswerContent() {
        return this.answerContent;
    }

    /**
     * 构造推断提示词。
     *
     * @return 推断提示词。
     */
    public String makeInferencePrompt() {
        return this.inference.replace("${content}", this.answerContent);
    }

    /**
     * 设置推断结果。
     *
     * @param result 推断结果。
     */
    public void setInferenceResult(String result) {
        this.inferenceResult = result;
    }

    /**
     * 选择回答。
     *
     * @param code 回答。
     * @return 回答。
     */
    public void chooseAnswer(String code) {
        if (this.isSingleChoice()) {
            for (Answer answer : this.answers) {
                if (answer.code.equalsIgnoreCase(code)) {
                    answer.chosen = true;
                }
                else {
                    answer.chosen = false;
                }
            }
        }
        else {
            for (Answer answer : this.answers) {
                if (answer.code.equalsIgnoreCase(code)) {
                    answer.chosen = !answer.chosen;
                    break;
                }
            }
        }
    }

    /**
     * 获取选中回答。
     *
     * @return 选中回答。
     */
    public Answer getChosenAnswer() {
        for (Answer answer : this.answers) {
            if (answer.chosen) {
                return answer;
            }
        }
        return null;
    }

    /**
     * 获取选中回答列表。
     *
     * @return 选中回答列表。
     */
    public List<Answer> getChosenAnswers() {
        List<Answer> answers = new ArrayList<>();
        for (Answer answer : this.answers) {
            if (answer.chosen) {
                answers.add(answer);
            }
        }
        return answers;
    }

    /**
     * 是否已答题。
     * @return 满足条件返回 {@code true} 。
     */
    public boolean hasAnswered() {
        for (Answer answer : this.answers) {
            if (answer.chosen) {
                return true;
            }
        }

        if (this.answerContent.length() > 0) {
            return true;
        }

        return false;
    }

    /**
     * 是否已完成答案选择。
     * @return 满足条件返回 {@code true} 。
     */
    public boolean hasChosen() {
        for (Answer answer : this.answers) {
            if (answer.chosen) {
                return true;
            }
        }
        return false;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("hidden", this.hidden);
        json.put("content", this.content);
        json.put("prompt", this.prompt);
        json.put("inference", this.inference);
        json.put("choice", this.choice);
        json.put("questionContent", this.questionContent);
        json.put("answerContent", this.answerContent);
        json.put("inferenceResult", this.inferenceResult);

        JSONArray array = new JSONArray();
        for (Answer answer : this.answers) {
            array.put(answer.toJSON());
        }
        json.put("answers", array);

        return json;
    }
}
