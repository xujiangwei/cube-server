/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 图表反应。
 *
 * <p>记录某项指标对图表的响应词与命中数量。</p>
 */
@Deprecated
public class ChartReaction implements JSONable {

    /** 序列号。 */
    public long sn;

    /** 主要。 */
    public final String[] primary;

    /** 次要。 */
    public String secondary = null;

    /** 第三。 */
    public String tertiary = null;

    /** 第四。 */
    public String quaternary = null;

    /** 系列名称。 */
    public final String seriesName;

    /** 时间戳。 */
    public final long timestamp;

    public ChartReaction(String[] primary, String seriesName, long timestamp) {
        this.sn = 0;
        this.primary = primary;
        this.seriesName = seriesName;
        this.timestamp = timestamp;
    }

    public ChartReaction(JSONObject json) {
        this.sn = json.has("sn") ? json.getLong("sn") : 0;

        JSONArray array = json.getJSONArray("primary");
        this.primary = new String[array.length()];
        for (int i = 0; i < array.length(); ++i) {
            this.primary[i] = array.getString(i);
        }

        this.seriesName = json.getString("seriesName");
        if (json.has("timestamp")) {
            this.timestamp = json.getLong("timestamp");
        }
        else {
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * 序列化主要反应。
     *
     * @return 主要反应的文本表示。
     */
    public String serializePrimary() {
        StringBuilder buf = new StringBuilder();
        for (String word : this.primary) {
            buf.append(word);
            buf.append(",");
        }
        buf.deleteCharAt(buf.length() - 1);
        return buf.toString();
    }

    /**
     * 统计命中关键词数量。
     *
     * @param words 待匹配的词列表。
     * @return 命中数量。
     */
    public int matchWordNum(List<String> words) {
        int num = 0;
        for (String word : words) {
            if (null != this.secondary && this.secondary.contains(word)) {
                ++num;
            }
            else if (null != this.tertiary && this.tertiary.contains(word)) {
                ++num;
            }
            else if (null != this.quaternary && this.quaternary.contains(word)) {
                ++num;
            }
        }
        return num;
    }

    /**
     * 判断指定对象是否与本对象相等。
     *
     * @param obj 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ChartReaction) {
            ChartReaction other = (ChartReaction) obj;
            return other.sn == this.sn && other.timestamp == this.timestamp;
        }

        return false;
    }

    /**
     * 计算哈希值。
     *
     * @return 哈希值。
     */
    @Override
    public int hashCode() {
        return (int)(this.sn + this.primary[0].hashCode() * 7 + this.timestamp);
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);

        JSONArray array = new JSONArray();
        for (String word : this.primary) {
            array.put(word);
        }
        json.put("primary", array);

        if (null != this.secondary) {
            json.put("secondary", this.secondary);
        }
        if (null != this.tertiary) {
            json.put("tertiary", this.tertiary);
        }
        if (null != this.quaternary) {
            json.put("quaternary", this.quaternary);
        }
        json.put("seriesName", this.seriesName);
        json.put("timestamp", this.timestamp);
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
