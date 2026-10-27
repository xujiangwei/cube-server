/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.auth.AuthConsts;
import org.json.JSONObject;

/**
 * 知识库的侧写。
 */
public class KnowledgeProfile extends Entity {

    /** 状态：正常。 */
    public final static int STATE_NORMAL = 0;

    /** 状态：只读。 */
    public final static int STATE_READ = 1;

    /** 状态：禁止。 */
    public final static int STATE_FORBIDDEN = 2;

    /** 联系人 ID。 */
    public final long contactId;

    /** 状态。 */
    public final int state;

    /**
     * 最大可用空间。
     */
    public final long maxSize;

    /** 范围。 */
    public final KnowledgeScope scope;

    public KnowledgeProfile(long id, long contactId, String domain, int state, long maxSize, KnowledgeScope scope) {
        super(id, domain);
        this.contactId = contactId;
        this.state = state;
        this.maxSize = maxSize;
        this.scope = scope;
    }

    public KnowledgeProfile(JSONObject json) {
        super(json);
        this.contactId = json.getLong("contactId");
        this.state = json.getInt("state");
        this.maxSize = json.getLong("maxSize");
        this.scope = KnowledgeScope.parse(json.getString("scope"));
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("contactId", this.contactId);
        json.put("state", this.state);
        json.put("maxSize", this.maxSize);
        json.put("scope", this.scope.name);
        return json;
    }

    /**
     * 创建占位。
     *
     * @return 占位。
     */
    public static KnowledgeProfile createDummy() {
        return new KnowledgeProfile(0, 0, AuthConsts.DEFAULT_DOMAIN, STATE_FORBIDDEN, 0,
                KnowledgeScope.Private);
    }
}
