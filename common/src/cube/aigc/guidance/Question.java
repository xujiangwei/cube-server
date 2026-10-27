/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.guidance;

import cell.util.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 引导问题。
 *
 * <p>引导流程中的一个提问节点：包含问题文本、候选答案、分组与跳转路由。
 * 同一问题可能有多组答案，按分组状态决定当前展示哪一组。</p>
 */
public class Question {

    private final String path;

    /** 问题编号。 */
    public final String sn;

    /** 前置条件，不满足时该问题不参与流程。 */
    public final Precondition precondition;

    /** 问题前缀，用于区分同一组内的不同提问。 */
    public final String prefix;

    /** 问题文本。 */
    public final String question;

    /** 是否为原始问题；{@code false} 表示由上一个答案派生出的追问。 */
    public final boolean original;

    /** 对回答的约束说明。 */
    public final String constraint;

    /** 候选答案列表。 */
    public final List<Answer> answers;

    /** 答案分组列表；非空时按分组选择答案。 */
    public final List<AnswerGroup> answerGroups;

    /** 跳转路由，决定回答后进入哪个节点。 */
    public final Router router;

    private List<String> answerContents;

    private Answer answer;

    private List<AnswerGroup> enabledGroups;

    private Map<String, Answer> groupAnswerMap;

    public Question(String path, JSONObject json) {
        this.path = path;
        this.sn = json.getString("sn");
        this.precondition = json.has("precondition") ? new Precondition(json.getJSONObject("precondition")): null;
        this.prefix = json.has("prefix") ? json.getString("prefix") : "";
        this.question = json.getString("question").trim().startsWith("file://") ?
                readQuestionFromFile(json.getString("question").trim()) : json.getString("question").trim();
        this.original = json.has("original") && json.getBoolean("original");
        this.constraint = json.has("constraint") ? json.getString("constraint") : null;
        JSONArray array = json.getJSONArray("answers");
        if (array.getJSONObject(0).has("group")) {
            this.answerGroups = new ArrayList<>();
            this.answers = null;
            for (int i = 0; i < array.length(); ++i) {
                this.answerGroups.add(new AnswerGroup(array.getJSONObject(i)));
            }
        }
        else {
            this.answers = new ArrayList<>();
            this.answerGroups = null;
            for (int i = 0; i < array.length(); ++i) {
                this.answers.add(new Answer(array.getJSONObject(i)));
            }
        }

        if (json.has("router")) {
            this.router = new Router(json.getJSONObject("router"));
        }
        else {
            this.router = null;
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
     * 追加答案内容。
     *
     * <p>用于流式生成时逐段累积文本；没有当前答案时忽略。</p>
     *
     * @param content 追加的文本片段。
     */
    public void appendAnswerContent(String content) {
        if (null == this.answerContents) {
            this.answerContents = new ArrayList<>();
        }
        this.answerContents.add(content);
    }

    /**
     * 记录用户选择的答案。
     *
     * @param answer 答案。
     */
    public void setAnswer(Answer answer) {
        this.answer = answer;
    }

    /**
     * 获取用户选择的答案。
     *
     * @return 答案；尚未回答返回 {@code null} 。
     */
    public Answer getAnswer() {
        return this.answer;
    }

    /**
     * 判断是否已回答。
     *
     * @return 已回答返回 {@code true} 。
     */
    public boolean hasAnswer() {
        return (null != this.answer);
    }

    /**
     * 过滤出启用的答案分组。
     *
     * @param groups 允许的分组名列表。
     * @return 启用的分组；无匹配时为空列表。
     */
    public List<AnswerGroup> enabledGroups(List<String> groups) {
        this.enabledGroups = new ArrayList<>();
        if (null == this.groupAnswerMap) {
            this.groupAnswerMap = new HashMap<>();
        }

        for (AnswerGroup answerGroup : this.answerGroups) {
            if (groups.contains(answerGroup.group)) {
                // 启用组
                this.enabledGroups.add(answerGroup);
            }
            else {
                // 未启用的使用默认答案
                answerGroup.state = AnswerGroup.STATE_ANSWERED;
                this.groupAnswerMap.put(answerGroup.group, answerGroup.getAnswer("false"));
            }
        }
        return this.enabledGroups;
    }

    /**
     * 记录指定分组的答案。
     *
     * @param group  分组名。
     * @param answer 答案。
     */
    public void setGroupAnswer(String group, Answer answer) {
        if (null == this.groupAnswerMap) {
            this.groupAnswerMap = new HashMap<>();
        }
        this.getAnswerGroup(group).state = AnswerGroup.STATE_ANSWERED;
        this.groupAnswerMap.put(group, answer);
    }

    /**
     * 获取指定分组的答案。
     *
     * @param group 分组名。
     * @return 答案；该分组未回答返回 {@code null} 。
     */
    public Answer getGroupAnswer(String group) {
        return this.groupAnswerMap.get(group);
    }

    /**
     * 按状态获取答案分组。
     *
     * @param state 分组状态。
     * @return 分组；无匹配返回 {@code null} 。
     */
    public AnswerGroup getAnswerGroupByState(int state) {
        for (AnswerGroup answerGroup : this.enabledGroups) {
            if (answerGroup.state == state) {
                return answerGroup;
            }
        }
        return null;
    }

    /**
     * 按名称获取答案分组。
     *
     * @param group 分组名。
     * @return 分组；无匹配返回 {@code null} 。
     */
    public AnswerGroup getAnswerGroup(String group) {
        if (null == this.answerGroups) {
            return null;
        }
        for (AnswerGroup answerGroup : this.answerGroups) {
            if (answerGroup.group.equalsIgnoreCase(group)) {
                return answerGroup;
            }
        }
        return null;
    }

    /**
     * 判断是否为分组问答。
     *
     * @return 存在答案分组返回 {@code true} 。
     */
    public boolean isAnswerGroup() {
        return (null != this.answerGroups);
    }

    /**
     * 判断所有答案分组是否都已完成。
     *
     * @return 全部分组已回答返回 {@code true} 。
     */
    public boolean hasAnswerGroupCompleted() {
        if (null == this.answerGroups) {
            return false;
        }

        int count = 0;
        for (AnswerGroup answerGroup : this.answerGroups) {
            if (answerGroup.state == AnswerGroup.STATE_ANSWERED) {
                ++count;
            }
        }
        return count == this.answerGroups.size();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        if (null != this.precondition) {
            json.put("precondition", this.precondition.toJSON());
        }
        json.put("prefix", this.prefix);
        json.put("question", this.question);
        json.put("original", this.original);
        if (null != this.constraint) {
            json.put("constraint", this.constraint);
        }

        JSONArray array = new JSONArray();
        if (null != this.answers) {
            for (Answer answer : this.answers) {
                array.put(answer.toJSON());
            }
        }
        else {
            for (AnswerGroup answerGroup : this.answerGroups) {
                array.put(answerGroup.toJSON());
            }
        }
        json.put("answers", array);

        if (null != this.router) {
            json.put("router", this.router.toJSON());
        }

        return json;
    }

    private String readQuestionFromFile(String fileLink) {
        String link = fileLink.replace("file://", "");
        Path filepath = Paths.get(this.path, link);
        try {
            byte[] data = Files.readAllBytes(filepath);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Logger.e(this.getClass(), "#readQuestionFromFile", e);
            return null;
        }
    }

    public class Precondition {

        public final List<String> items;

        public final String condition;

        public Precondition(JSONObject json) {
            this.items = new ArrayList<>();
            JSONArray array = json.getJSONArray("items");
            for (int i = 0; i < array.length(); ++i) {
                this.items.add(array.getString(i));
            }
            this.condition = json.getString("condition");
        }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            JSONArray array = new JSONArray();
            for (String item : this.items) {
                array.put(item);
            }
            json.put("items", array);
            json.put("condition", this.condition);
            return json;
        }
    }
}
