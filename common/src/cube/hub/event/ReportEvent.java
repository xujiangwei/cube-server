/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.Contact;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态报告。
 */
public class ReportEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "Report";

    /**
     * 可管理的 App 总数。
     */
    private int totalAppNum;

    /**
     * 空闲的 App 数量。
     */
    private int idleAppNum;

    /**
     * 已管理的账号。
     */
    private List<Contact> managedAccounts;

    /**
     * 频道码映射。
     */
    private Map<String, Contact> channelCodeMap;

    public ReportEvent(int totalAppNum, int idleAppNum, List<Contact> managedAccounts) {
        super(NAME);
        this.totalAppNum = totalAppNum;
        this.idleAppNum = idleAppNum;
        this.managedAccounts = managedAccounts;
        this.channelCodeMap = new HashMap<>();
    }

    public ReportEvent(JSONObject json) {
        super(json);
        this.totalAppNum = json.getInt("total");
        this.idleAppNum = json.getInt("idle");

        this.managedAccounts = new ArrayList<>();
        JSONArray array = json.getJSONArray("accounts");
        for (int i = 0; i < array.length(); ++i) {
            Contact account = new Contact(array.getJSONObject(i));
            this.managedAccounts.add(account);
        }

        this.channelCodeMap = new HashMap<>();
        if (json.has("channelCodes")) {
            JSONObject map = json.getJSONObject("channelCodes");
            for (String key : map.keySet()) {
                JSONObject value = map.getJSONObject(key);
                this.channelCodeMap.put(key, new Contact(value));
            }
        }
    }

    /**
     * 获取应用总数。
     *
     * @return 数量。
     */
    public int getTotalAppNum() {
        return this.totalAppNum;
    }

    /**
     * 获取空闲应用数。
     *
     * @return 数量。
     */
    public int getIdleAppNum() {
        return this.idleAppNum;
    }

    /**
     * 获取托管账号列表。
     *
     * @return 账号列表；无托管时为空列表。
     */
    public List<Contact> getManagedAccounts() {
        return this.managedAccounts;
    }

    /**
     * 添加托管账号。
     *
     * @param account 联系人。
     */
    public void addManagedAccount(Contact account) {
        this.managedAccounts.add(account);
    }

    /**
     * 移除托管账号。
     *
     * @param account 联系人。
     */
    public void removeManagedAccount(Contact account) {
        synchronized (this) {
            for (int i = 0; i < this.managedAccounts.size(); ++i) {
                Contact current = this.managedAccounts.get(i);
                if (account.getExternalId().equals(current.getExternalId())) {
                    this.managedAccounts.remove(i);
                    // 空闲 App 数量增加
                    ++this.idleAppNum;
                    break;
                }
            }
        }
    }

    /**
     * 判断是否为托管账号。
     *
     * @param account 联系人。
     * @return 是托管账号返回 {@code true} 。
     */
    public boolean hasManagedAccount(Contact account) {
        synchronized (this) {
            for (int i = 0; i < this.managedAccounts.size(); ++i) {
                Contact current = this.managedAccounts.get(i);
                if (account.getExternalId().equals(current.getExternalId())) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * 建立通道码与账号的映射。
     *
     * @param channelCode 通道码。
     * @param account     联系人。
     */
    public void putChannelCode(String channelCode, Contact account) {
        this.channelCodeMap.put(channelCode, account);
    }

    /**
     * 移除通道码。
     *
     * @param channelCode 通道码。
     */
    public Contact removeChannelCode(String channelCode) {
        return this.channelCodeMap.remove(channelCode);
    }

    /**
     * 按通道码获取账号。
     *
     * @param channelCode 通道码。
     * @return 联系人；无映射返回 {@code null} 。
     */
    public Contact getAccount(String channelCode) {
        return this.channelCodeMap.get(channelCode);
    }

    /**
     * 判断是否通道码。
     *
     * @param channelCode 通道码。
     * @return 通道码。
     */
    public boolean hasChannelCode(String channelCode) {
        return this.channelCodeMap.containsKey(channelCode);
    }

    /**
     * 获取通道码。
     *
     * @return 通道码。
     */
    public Map<String, Contact> getChannelCodes() {
        return this.channelCodeMap;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("total", this.totalAppNum);
        json.put("idle", this.idleAppNum);

        JSONArray array = new JSONArray();
        for (Contact account : this.managedAccounts) {
            array.put(account.toCompactJSON());
        }
        json.put("accounts", array);

        if (!this.channelCodeMap.isEmpty()) {
            JSONObject map = new JSONObject();
            for (Map.Entry<String, Contact> entry : this.channelCodeMap.entrySet()) {
                map.put(entry.getKey(), entry.getValue().toCompactJSON());
            }
            json.put("channelCodes", map);
        }

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
        JSONObject json = super.toCompactJSON();
        json.put("total", this.totalAppNum);
        json.put("idle", this.idleAppNum);

        JSONArray array = new JSONArray();
        for (Contact account : this.managedAccounts) {
            array.put(account.toCompactJSON());
        }
        json.put("accounts", array);
        return json;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("\n");
        buf.append("---------------- Report ----------------\n");

        if (null != this.getDescription()) {
            buf.append("Pretender : ");
            buf.append(this.getDescription().getPretender().getId());
            buf.append("\n");
        }

        buf.append("Statistics: ");
        buf.append(this.totalAppNum - this.idleAppNum);
        buf.append(" / ");
        buf.append(this.totalAppNum);
        buf.append("\n");

        if (!this.channelCodeMap.isEmpty()) {
            for (Map.Entry<String, Contact> entry : this.channelCodeMap.entrySet()) {
                buf.append(entry.getKey());
                buf.append(" -> ");
                buf.append(entry.getValue().getName());
                buf.append("\n");
            }
        }

        buf.append("----------------------------------------\n");
        return buf.toString();
    }
}
