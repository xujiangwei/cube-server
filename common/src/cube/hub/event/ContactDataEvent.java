/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.Contact;
import org.json.JSONObject;

/**
 * 联系人数据。
 */
public class ContactDataEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "ContactData";

    private Contact contact;

    public ContactDataEvent(Contact account, Contact contact) {
        super(NAME, account);
        this.contact = contact;
    }

    public ContactDataEvent(JSONObject json) {
        super(json);
        this.contact = new Contact(json.getJSONObject("contact"));
    }

    /**
     * 获取联系人。
     *
     * @return 联系人。
     */
    public Contact getContact() {
        return this.contact;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("contact", this.contact.toJSON());
        return json;
    }
}
