/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 联系人搜索结果。
 */
public class ContactSearchResult implements JSONable {

    private long timestamp;

    private String keyword;

    private List<Contact> contactList;

    private List<Group> groupList;

    public ContactSearchResult(String keyword) {
        this.timestamp = System.currentTimeMillis();
        this.keyword = keyword;
        this.contactList = new ArrayList<>();
        this.groupList = new ArrayList<>();
    }

    /**
     * 获取时间戳。
     *
     * @return 时间戳。
     */
    public long getTimestamp() {
        return this.timestamp;
    }

    /**
     * 获取联系人列表。
     *
     * @return 联系人列表。
     */
    public List<Contact> getContactList() {
        return this.contactList;
    }

    /**
     * 添加联系人。
     *
     * @param contact 联系人。
     */
    public void addContact(Contact contact) {
        if (this.contactList.contains(contact)) {
            return;
        }

        this.contactList.add(contact);
    }

    /**
     * 获取组列表。
     *
     * @return 组列表。
     */
    public List<Group> getGroupList() {
        return this.groupList;
    }

    /**
     * 添加组。
     *
     * @param group 组。
     */
    public void addGroup(Group group) {
        if (this.groupList.contains(group)) {
            return;
        }

        this.groupList.add(group);
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();

        json.put("keyword", this.keyword);

        JSONArray contacts = new JSONArray();
        for (Contact contact : this.contactList) {
            contacts.put(contact.toCompactJSON());
        }
        json.put("contactList", contacts);

        JSONArray groups = new JSONArray();
        for (Group group : this.groupList) {
            groups.put(group.toCompactJSON());
        }
        json.put("groupList", groups);

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
