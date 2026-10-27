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

/**
 * 问卷。
 */
public class Questionnaire {

    /** 名称。 */
    public String name;

    /** 显示名称。 */
    public String displayName;

    /** 指令。 */
    public String instruction;

    /** 打开。 */
    public boolean open = false;

    /** 问题章节列表。 */
    public List<QuestionSection> questionSections;

    /** 计分脚本内容。 */
    protected String scoringScript;

    public Questionnaire() {
    }

    /**
     * 按结构数据构建问卷。
     *
     * @param structure 问卷结构 JSON 。
     */
    protected void build(JSONObject structure) {
        this.name = structure.getString("name");
        this.displayName = structure.has("displayName") ? structure.getString("displayName") : this.name;
        this.instruction = structure.has("instruction") ? structure.getString("instruction") : "";
        this.open = structure.has("open") && structure.getBoolean("open");
        this.questionSections = new ArrayList<>();
        if (structure.has("sections")) {
            JSONArray array = structure.getJSONArray("sections");
            for (int i = 0; i < array.length(); ++i) {
                QuestionSection section = new QuestionSection(array.getJSONObject(i));
                this.questionSections.add(section);
            }
        }
        if (structure.has("scoringScript")) {
            this.scoringScript = structure.getString("scoringScript");
        }
    }

    /**
     * 选择回答。
     *
     * @param sn 回答。
     * @param code 回答。
     * @return 回答。
     */
    public boolean chooseAnswer(int sn, String code) {
        Question question = this.getQuestion(sn);
        if (null == question) {
            return false;
        }

        if (question.isSingleChoice()) {
            for (Answer answer : question.answers) {
                if (answer.code.equals(code)) {
                    answer.chosen = true;
                }
                else {
                    answer.chosen = false;
                }
            }
        }
        else {
            for (Answer answer : question.answers) {
                if (answer.code.equals(code)) {
                    answer.chosen = !answer.chosen;
                    break;
                }
            }
        }

        return true;
    }

    /**
     * 判断是否完成。
     *
     * @return 完成。
     */
    public boolean isComplete() {
        int num = 0;
        int count = 0;
        for (QuestionSection section : this.questionSections) {
            for (Question question : section.getQuestions()) {
                num += 1;
                if (question.hasAnswered()) {
                    count += 1;
                }
            }
        }
        return num == count;
    }

    /**
     * 获取回答。
     *
     * @param sn 回答。
     * @return 回答。
     */
    public Answer getAnswer(int sn) {
        Question question = getQuestion(sn);
        if (null == question) {
            return null;
        }
        return question.getChosenAnswer();
    }

    /**
     * 获取全部选中回答列表。
     *
     * @return 全部选中回答列表。
     */
    public List<Answer> getAllChosenAnswers() {
        List<Answer> answers = new ArrayList<>();
        for (QuestionSection section : this.questionSections) {
            for (Question question : section.getQuestions()) {
                Answer answer = question.getChosenAnswer();
                if (null == answer) {
                    continue;
                }
                answers.add(answer);
            }
        }
        return answers;
    }

    /**
     * 获取问题。
     *
     * @param sn 问题。
     * @return 问题。
     */
    public Question getQuestion(int sn) {
        for (QuestionSection section : this.questionSections) {
            for (Question question : section.getQuestions()) {
                if (question.sn == sn) {
                    return question;
                }
            }
        }
        return null;
    }

    /**
     * 获取问题列表。
     *
     * @return 问题列表。
     */
    public List<Question> getQuestions() {
        List<Question> result = new ArrayList<>();
        for (QuestionSection section : this.questionSections) {
            result.addAll(section.getQuestions());
        }
        return result;
    }

    /**
     * 统计问题列表。
     *
     * @return 数量。
     */
    public int numQuestions() {
        int num = 0;
        for (QuestionSection section : this.questionSections) {
            num += section.numQuestions();
        }
        return num;
    }

    /**
     * 获取隐藏题目。
     *
     * @return 隐藏题目。
     */
    public List<Question> getHiddenQuestions() {
        List<Question> result = new ArrayList<>();
        for (QuestionSection section : this.questionSections) {
            result.addAll(section.getHiddenQuestions());
        }
        return result;
    }

    /**
     * 转换为Markdown。
     *
     * @return Markdown。
     */
    public String toMarkdown() {
        StringBuilder buf = new StringBuilder();
        buf.append("# ").append(this.name).append("\n\n");
        buf.append(this.instruction).append("\n\n");
        buf.append("\n");
        for (QuestionSection section : this.questionSections) {
            buf.append("## ").append(section.title).append("\n\n");
            buf.append(section.content).append("\n\n");

            for (Question question : section.getQuestions()) {
                if (question.content.length() > 0) {
                    buf.append("* ").append(question.sn).append("、").append(question.content).append("  \n");
                    for (Answer answer : question.answers) {
                        buf.append(answer.content).append("  \n");
                    }
                    buf.delete(buf.length() - 3, buf.length());
                    buf.append("\n");
                }
                else {
                    buf.append("* ").append(question.sn).append("、");
                    for (Answer answer : question.answers) {
                        buf.append(answer.content).append("    ");
                    }
                    buf.delete(buf.length() - 4, buf.length());
                    buf.append("\n");
                }

                buf.append("\n");
            }
            buf.append("\n");
        }
        return buf.toString();
    }
}
