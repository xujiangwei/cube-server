/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import org.json.JSONObject;

/**
 * 客户端提交登录二维码信令。
 */
public class LoginQRCodeSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "LoginQRCode";

    private long timestamp;

    public LoginQRCodeSignal(String channelCode) {
        super(NAME);
        this.setCode(channelCode);
        this.timestamp = System.currentTimeMillis();
    }

    public LoginQRCodeSignal(JSONObject json) {
        super(json);
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 获取时间戳。
     *
     * @return 时间戳。
     */
    public long getTimestamp() {
        return this.timestamp;
    }
}
