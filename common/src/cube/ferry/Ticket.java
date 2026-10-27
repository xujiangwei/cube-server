/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

import cell.core.talk.TalkContext;
import cube.common.Domain;
import org.json.JSONObject;

/**
 * 用于标记 Ferry 的描述。
 */
public class Ticket {

    /** 域。 */
    public final Domain domain;

    /** 授权信息。 */
    public final JSONObject licence;

    /** 会话上下文。 */
    public final TalkContext talkContext;

    /** 会话 ID。 */
    public final long sessionId;

    public Ticket(String domainName, JSONObject licence, TalkContext talkContext) {
        this.domain = new Domain(domainName);
        this.licence = licence;
        this.talkContext = talkContext;
        this.sessionId = (null != talkContext) ? talkContext.getSessionId() : 0;
    }

    /**
     * 获取授权起始时间。
     *
     * @return 时间戳，单位毫秒。
     */
    public long getLicenceBeginning() {
        return this.licence.getLong("beginning");
    }

    /**
     * 获取授权时长。
     *
     * @return 时长，单位毫秒。
     */
    public long getLicenceDuration() {
        return this.licence.getLong("duration");
    }

    /**
     * 获取授权数量上限。
     *
     * @return 上限值。
     */
    public int getLicenceLimit() {
        return this.licence.getInt("limit");
    }
}
