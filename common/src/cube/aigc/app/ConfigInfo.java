/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.app;

import cube.aigc.ContactPreference;
import cube.aigc.Usage;
import cube.aigc.ModelConfig;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 配置。
 */
public class ConfigInfo implements JSONable {

    /** 可用的模型配置列表。 */
    public List<ModelConfig> models;

    /** 已启用的通知列表。 */
    public List<Notification> notifications;

    /** 联系人偏好设置。 */
    public ContactPreference contactPreference;

    /** 请求超时时间，单位毫秒。 */
    public long timeoutMs;

    /** 当前用量。 */
    public int usage;

    /** 按模型拆分的用量列表。 */
    public List<Usage> usages;

    public ConfigInfo(List<ModelConfig> models, List<Notification> notifications, ContactPreference contactPreference) {
        this.models = new ArrayList<>(models);
        this.notifications = notifications;
        this.contactPreference = contactPreference;
        this.timeoutMs = 60 * 1000;
        this.usage = 0;
        this.fixModelsWithPreference();
    }

    public ConfigInfo(JSONObject json) {
        this.models = new ArrayList<>();
        JSONArray modelArray = json.getJSONArray("models");
        for (int i = 0; i < modelArray.length(); ++i) {
            ModelConfig mc = new ModelConfig(modelArray.getJSONObject(i));
            this.models.add(mc);
        }

        this.notifications = new ArrayList<>();
        JSONArray notificationArray = json.getJSONArray("notifications");
        for (int i = 0; i < notificationArray.length(); ++i) {
            Notification notification = new Notification(notificationArray.getJSONObject(i));
            this.notifications.add(notification);
        }

        this.timeoutMs = json.getLong("timeoutMs");
        this.usage = json.getInt("usage");

        if (json.has("preference")) {
            this.contactPreference = new ContactPreference(json.getJSONObject("preference"));
            this.fixModelsWithPreference();
        }

        if (json.has("usages")) {
            JSONArray array = json.getJSONArray("usages");
            this.usages = new ArrayList<>();
            for (int i = 0; i < array.length(); ++i) {
                this.usages.add(new Usage(array.getJSONObject(i)));
            }
        }
    }

    private void fixModelsWithPreference() {
        if (null == this.contactPreference) {
            return;
        }

        Iterator<ModelConfig> iter = this.models.iterator();
        while (iter.hasNext()) {
            ModelConfig mc = iter.next();
            if (!this.contactPreference.containsModel(mc.getModel())) {
                // 删除不包含的模型配置
                iter.remove();
            }
        }
    }

    /**
     * 把模型配置转为数组。
     *
     * @return 模型 JSON 数组。
     */
    public JSONArray getModelsAsJSONArray() {
        JSONArray array = new JSONArray();
        for (ModelConfig mc : this.models) {
            array.put(mc.toJSON());
        }
        return array;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();

        JSONArray modelArray = new JSONArray();
        for (ModelConfig model : this.models) {
            modelArray.put(model.toJSON());
        }

        JSONArray notificationArray = new JSONArray();
        for (Notification notification : this.notifications) {
            notificationArray.put(notification.toJSON());
        }

        if (null != this.contactPreference) {
            json.put("preference", this.contactPreference.toJSON());
        }

        if (null != this.usages) {
            JSONArray array = new JSONArray();
            for (Usage usage : this.usages) {
                array.put(usage.toCompactJSON());
            }
            json.put("usages", array);
        }

        json.put("models", modelArray);
        json.put("notifications", notificationArray);
        json.put("timeoutMs", this.timeoutMs);
        json.put("usage", this.usage);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
