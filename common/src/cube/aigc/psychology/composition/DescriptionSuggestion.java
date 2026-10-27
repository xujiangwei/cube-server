/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.aigc.psychology.Term;
import cube.common.JSONable;
import org.json.JSONObject;

public class DescriptionSuggestion implements JSONable {

    /** 术语。 */
    public Term term;

    /** 描述。 */
    public String description;

    /** 行为。 */
    public String behavior;

    /** 建议。 */
    public String suggestion;

    public DescriptionSuggestion(JSONObject json) {
        this.term = Term.parse(json.getString("term"));
        this.description = json.getString("description");
        this.behavior = json.getString("behavior");
        this.suggestion = json.getString("suggestion");
    }

    public DescriptionSuggestion(Term term, String description, String behavior, String suggestion) {
        this.term = term;
        this.description = description;
        this.behavior = behavior;
        this.suggestion = suggestion;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("term", this.term.word);
        json.put("description", this.description);
        json.put("behavior", this.behavior);
        json.put("suggestion", this.suggestion);
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
