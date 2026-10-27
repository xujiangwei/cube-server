/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import org.json.JSONObject;

/**
 * 查询通道码。
 */
public class QueryChannelCodeSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "QueryChannelCode";

    private String accountId;

    public QueryChannelCodeSignal(String accountId) {
        super(NAME);
        this.accountId = accountId;
    }

    public QueryChannelCodeSignal(JSONObject json) {
        super(json);
        this.accountId = json.getString("accountId");
    }

    /**
     * 获取账号 ID 。
     *
     * @return 账号标识。
     */
    public String getAccountId() {
        return this.accountId;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("accountId", this.accountId);
        return json;
    }
}
