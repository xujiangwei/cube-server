/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.report;

import org.json.JSONObject;

/**
 * 服务器状态报告。
 */
public class ServerReport extends Report {

    public ServerReport() {
        super("ServerReport");
    }

    /**
     * 设置绑定地址与端口。
     *
     * @param bindingAddress 绑定地址。
     * @param bindingPort    端口号。
     */
    public void setBinding(String bindingAddress, int bindingPort) {

    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();

        return json;
    }
}
