/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import org.json.JSONObject;

import java.io.File;

/**
 * 登录二维码事件。
 */
public class LoginQRCodeEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "LoginQRCode";

    private Long pretenderId;

    public LoginQRCodeEvent(long sn, String channelCode, File file) {
        super(sn, NAME, file);
        setCode(channelCode);
    }

    public LoginQRCodeEvent(JSONObject json) {
        super(json);
    }

    /**
     * 设置冒充者 ID。
     *
     * @param pretenderId 冒充者 ID。
     */
    public void setPretenderId(Long pretenderId) {
        this.pretenderId = pretenderId;
    }

    /**
     * 获取冒充者 ID。
     *
     * @return 冒充者 ID。
     */
    @Override
    public Long getPretenderId() {
        return this.pretenderId;
    }
}
