/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.Contact;
import org.json.JSONObject;

/**
 * 账号信息事件。
 */
public class AccountEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "Account";

    public AccountEvent(Contact account) {
        super(NAME, account);
    }

    public AccountEvent(JSONObject json) {
        super(json);
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
        return super.toCompactJSON();
    }
}
