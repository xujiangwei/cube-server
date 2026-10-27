/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.guidance;

import cell.util.log.Logger;
import cube.common.state.AIGCStateCode;
import cube.util.ConfigUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 引导流程抽象基类。
 *
 * <p>按脚本描述装配问题节点与跳转关系，子类实现 {@link #input} 决定回答如何推进流程。
 * 流程可被中断，{@link #stop()} 负责清理当前会话状态。</p>
 */
public abstract class AbstractGuideFlow implements GuideFlowable {

    /** 脚本文件路径。 */
    protected final String path;

    /** 流程名称，用于匹配与日志。 */
    protected String name;

    /** 展示名称。 */
    protected String displayName;

    /**
     * 匹配用关键词。第一个关键为主关键词，会用于最终评估时内容输出。
     */
    protected List<String> keywords;

    /** 流程的开场指令，进入流程时下发给模型。 */
    protected String instruction;

    /**
     * 当用户回答的答案偏离既定目标时，对用户进行解释时的提示词前缀。
     */
    protected String explainPrefix;

    /**
     * 没有结果时的话术。
     */
    protected String noResults;

    /**
     * 用户回答无法进行推理时的话术。
     */
    protected String questionableResults;

    /** 章节列表，按顺序包含各问题节点。 */
    protected List<GuidanceSection> sectionList;

    /** 流程事件监听器。 */
    protected GuideListener listener;

    public AbstractGuideFlow(File file) {
        this.path = file.getParent();
        JSONObject jsonData = ConfigUtils.readJsonFile(file.getAbsolutePath());
        if (null == jsonData) {
            Logger.e(this.getClass(), "#load - Read file failed: " + file.getAbsolutePath());
            return;
        }
        this.parse(jsonData);
    }

    public AbstractGuideFlow(String path, JSONObject json) {
        this.path = path;
        this.parse(json);
    }

    private void parse(JSONObject json) {
        this.name = json.getString("name");
        this.displayName = json.getString("displayName");
        this.keywords = new ArrayList<>();
        JSONArray array = json.getJSONArray("keywords");
        for (int i = 0; i < array.length(); ++i) {
            this.keywords.add(array.getString(i));
        }
        this.instruction = json.getString("instruction");
        this.explainPrefix = json.getString("explainPrefix");
        this.noResults = json.getString("noResults");
        this.questionableResults = json.getString("questionableResults");
        this.sectionList = new ArrayList<>();
        array = json.getJSONArray("sections");
        for (int i = 0; i < array.length(); ++i) {
            this.sectionList.add(new GuidanceSection(path, array.getJSONObject(i)));
        }
    }

    /**
     * 获取流程名称。
     *
     * @return 流程名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取展示名称。
     *
     * @return 展示名称；未设置时返回流程名称。
     */
    public String getDisplayName() {
        return this.displayName;
    }

    /**
     * 获取流程的全部关键词。
     *
     * @return 关键词列表；无关键词时为空列表。
     */
    public List<String> getKeywords() {
        return this.keywords;
    }

    /**
     * 获取主关键词。
     *
     * @return 主关键词；无关键词时返回 {@code null} 。
     */
    public String getMainKeyword() {
        return this.keywords.get(0);
    }

    /**
     * 获取开场指令。
     *
     * @return 指令文本。
     */
    public String getInstruction() {
        return this.instruction;
    }

    /**
     * 获取解释前缀。
     *
     * @return 前缀文本；未配置时返回 {@code null} 。
     */
    public String getExplainPrefix() {
        return this.explainPrefix;
    }

    /**
     * 设置流程监听器。
     *
     * @param listener 监听器。
     */
    public void setListener(GuideListener listener) {
        this.listener = listener;
    }

    /**
     * 按编号获取问题。
     *
     * <p>遍历全部章节查找，编号在流程内唯一。</p>
     *
     * @param sn 问题编号。
     * @return 问题；不存在返回 {@code null} 。
     */
    public Question getQuestion(String sn) {
        for (GuidanceSection section : this.sectionList) {
            Question question = section.getQuestion(sn);
            if (null != question) {
                return question;
            }
        }
        return null;
    }

    /**
     * 设置答案。
     * @param sn 序列号。
     * @param answerCode answerCode。
     */
    public void setQuestionAnswer(String sn, String answerCode) {
        Question question = getQuestion(sn);
        if (null == question) {
            Logger.w(this.getClass(), "#setQuestionAnswer - Can NOT find question: " + sn);
            return;
        }

        for (Answer answer : question.answers) {
            if (answer.code.equalsIgnoreCase(answerCode)) {
                question.setAnswer(answer);
                break;
            }
        }
    }

    /**
     * 统计命中关键词数量。
     *
     * @param list 待匹配的词列表。
     * @return 命中数量。
     */
    public int hitKeywords(List<String> list) {
        int hit = 0;
        for (String keyword : this.keywords) {
            if (list.contains(keyword)) {
                ++hit;
            }
        }
        return hit;
    }

    /**
     * 判断流程是否已完成。
     *
     * @return 全部问题已回答返回 {@code true} 。
     */
    public boolean hasCompleted() {
        int completed = 0;
        for (GuidanceSection section : this.sectionList) {
            if (section.hasTerminated()) {
                // 已终止
                return true;
            }

            if (section.hasCompleted()) {
                ++completed;
            }
        }
        return (completed == this.sectionList.size());
    }

    /**
     * 停止流程。
     *
     * <p>清理当前会话状态并释放占用的资源，允许重复调用。</p>
     */
    public abstract void stop();

    /**
     * 提交回答并推进流程。
     *
     * <p>子类决定该回答是否满足跳转条件、是否结束流程或需要追问。</p>
     *
     * @param currentQuestionAnswer 当前问题的回答文本。
     * @param candidate             候选答案。
     * @return 状态码；{@code AIGCStateCode.OK} 表示已被流程接受。
     */
    public abstract AIGCStateCode input(String currentQuestionAnswer, Answer candidate);

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("path", this.path);
        json.put("name", this.name);
        json.put("displayName", this.displayName);
        JSONArray array = new JSONArray();
        for (String keyword : this.keywords) {
            array.put(keyword);
        }
        json.put("keywords", array);
        json.put("instruction", this.instruction);
        json.put("explainPrefix", this.explainPrefix);
        json.put("noResults", this.noResults);
        json.put("questionableResults", this.questionableResults);
        array = new JSONArray();
        for (GuidanceSection section : this.sectionList) {
            array.put(section.toJSON());
        }
        json.put("sections", array);
        return json;
    }
}
