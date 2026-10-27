/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import cell.util.Base64;
import cell.util.log.Logger;
import cube.common.entity.Contact;
import cube.common.entity.ConversationType;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 发送消息信令。
 */
public class SendMessageSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "SendMessage";

    private Contact account;

    private ConversationType conversationType;

    private String partnerId;

    private String groupName;

    private String text;

    private String fileCode;

    public SendMessageSignal(String channelCode,
                             ConversationType conversationType, String idOrName) {
        super(NAME);
        setCode(channelCode);

        if (conversationType == ConversationType.Contact) {
            this.partnerId = idOrName;
        }
        else if (conversationType == ConversationType.Group) {
            this.groupName = idOrName;
        }
    }

    public SendMessageSignal(JSONObject json) {
        super(json);

        if (json.has("account")) {
            this.account = new Contact(json.getJSONObject("account"));
        }

        if (json.has("partnerId")) {
            this.partnerId = json.getString("partnerId");
            this.conversationType = ConversationType.Contact;
        }

        if (json.has("groupName")) {
            this.groupName = json.getString("groupName");
            this.conversationType = ConversationType.Group;
        }

        if (json.has("text")) {
            try {
                byte[] bytes = Base64.decode(json.getString("text"));
                this.text = new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Logger.e(SendMessageSignal.class, "#SendMessageSignal - I/O error", e);
            }
        }

        if (json.has("fileCode")) {
            this.fileCode = json.getString("fileCode");
        }
    }

    /**
     * 获取账号。
     *
     * @return 联系人。
     */
    public Contact getAccount() {
        return this.account;
    }

    /**
     * 设置账号。
     *
     * @param account 联系人。
     */
    public void setAccount(Contact account) {
        this.account = account;
    }

    /**
     * 获取会话类型。
     *
     * @return 会话类型。
     */
    public ConversationType getConversationType() {
        return this.conversationType;
    }

    /**
     * 获取对端 ID 。
     *
     * @return 对端标识。
     */
    public String getPartnerId() {
        return this.partnerId;
    }

    /**
     * 获取组名称。
     *
     * @return 组名称。
     */
    public String getGroupName() {
        return this.groupName;
    }

    /**
     * 设置文本。
     *
     * @param text 文本。
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * 获取文本。
     *
     * @return 文本。
     */
    public String getText() {
        return this.text;
    }

    /**
     * 设置文件码。
     *
     * @param fileCode 文件码。
     */
    public void setFileCode(String fileCode) {
        this.fileCode = fileCode;
    }

    /**
     * 获取文件码。
     *
     * @return 文件码。
     */
    public String getFileCode() {
        return this.fileCode;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();

        if (null != this.account) {
            json.put("account", this.account.toCompactJSON());
        }

        if (null != this.partnerId) {
            json.put("partnerId", this.partnerId);
        }

        if (null != this.groupName) {
            json.put("groupName", this.groupName);
        }

        if (null != this.text) {
            String base64 = Base64.encodeBytes(this.text.getBytes(StandardCharsets.UTF_8));
            json.put("text", base64);
        }

        if (null != this.fileCode) {
            json.put("fileCode", this.fileCode);
        }

        return json;
    }
}
