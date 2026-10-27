/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.aigc.psychology.consultation.ConsultationTheme;
import cube.aigc.psychology.Attribute;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 咨询策略。
 */
public class CounselingStrategy implements JSONable {

    public enum ConsultingAction {
        /**
         * 一般性策略。
         */
        General("general"),

        /**
         * 执行分析策略。
         */
        Analysis("analysis"),

        /**
         * 执行建议策略。
         */
        Suggestion("suggestion"),

        /**
         * 执行对话指引策略。
         */
        Conversation("conversation"),

        ;

        public final String code;

        ConsultingAction(String code) {
            this.code = code;
        }

        public static ConsultingAction parse(String codeOrName) {
            for (ConsultingAction action : ConsultingAction.values()) {
                if (action.code.equalsIgnoreCase(codeOrName)) {
                    return action;
                }
            }
            return ConsultingAction.General;
        }
    }

    /** 索引。 */
    public int index;

    /** 属性。 */
    public Attribute attribute;

    /** 主题。 */
    public ConsultationTheme theme;

    /** 流名称。 */
    public String streamName;

    /** 咨询动作。 */
    public ConsultingAction consultingAction;

    /** 内容。 */
    public String content;

    /** 时间戳。 */
    public long timestamp;

    public CounselingStrategy(int index, Attribute attribute, ConsultationTheme theme, String streamName,
                              ConsultingAction consultingAction, String content) {
        this.index = index;
        this.attribute = attribute;
        this.theme = theme;
        this.streamName = streamName;
        this.consultingAction = consultingAction;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public CounselingStrategy(JSONObject json) {
        this.index = json.getInt("index");
        this.attribute = new Attribute(json.getJSONObject("attribute"));
        this.theme = ConsultationTheme.parse(json.getString("theme"));
        this.streamName = json.getString("streamName");
        this.consultingAction = ConsultingAction.parse(json.getString("consultingAction"));
        this.content = json.getString("content");
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
        json.put("index", this.index);
        json.put("attribute", this.attribute.toJSON());
        json.put("theme", this.theme.code);
        json.put("streamName", this.streamName);
        json.put("consultingAction", this.consultingAction.code);
        json.put("content", this.content);
        json.put("timestamp", this.timestamp);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>用于传输体积敏感的场合，字段较完整序列化更少。</p>
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
