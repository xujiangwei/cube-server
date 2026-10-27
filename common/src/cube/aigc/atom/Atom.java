/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.atom;

import cube.common.JSONable;
import org.json.JSONObject;

import java.util.List;

/**
 * 原子数据。
 *
 * <p>图表的最小数据单元：由标签、日期与数值组成，聚合后生成位图序列。
 * 日期拆分为年/月/日三个字符串字段，便于按维度匹配。</p>
 */
public class Atom implements JSONable {

    /** 序列号。 */
    public final long sn;

    /** 数据标签，聚合时按标签归类。 */
    public String label;

    /** 年份。 */
    public String year;
    /** 月份。 */
    public String month;
    /** 日。 */
    public String date;

    /** 数值。 */
    public int value;

    /** 当前标签的匹配次数，用于计算热度。 */
    public int currentLabelMatchingNum;

    public Atom(long sn, String label, String year, String month, String date, int value) {
        this.sn = sn;
        this.label = label;
        this.year = year;
        this.month = month;
        this.date = date;
        this.value = value;
    }

    public Atom(JSONObject json) {
        this.sn = 0;
        this.label = json.getString("label");
        this.year = json.getString("year");
        this.month = json.getString("month");
        this.date = json.getString("date");
        this.value = json.has("value") ? json.getInt("value") : 0;
    }

    /**
     * 获取年份数值。
     *
     * @return 年份；无法解析返回 0 。
     */
    public int getYear() {
        try {
            return Integer.parseInt(this.year.replace("年", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取月份数值。
     *
     * @return 月份；无法解析返回 0 。
     */
    public int getMonth() {
        try {
            return Integer.parseInt(this.month.replace("月", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取日数值。
     *
     * @return 日；无法解析返回 0 。
     */
    public int getDate() {
        try {
            return Integer.parseInt(this.date.replace("日", "").replace("号", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 序列化日期。
     *
     * @return 形如 {@code 2025-08-24} 的字符串；字段缺失时按可用部分拼接。
     */
    public String serializeDate() {
        StringBuilder buf = new StringBuilder();
        if (null != this.year) {
            buf.append(this.year);
        }
        if (null != this.month) {
            buf.append(this.month);
        }
        if (null != this.date) {
            buf.append(this.date);
        }
        return buf.toString();
    }

    /**
     * 格式化完整日期。
     *
     * @return 中文日期文本，如“2025年8月24日”。
     */
    public String formatDate() {
        StringBuilder buf = new StringBuilder();
        if (null != this.year) {
            buf.append(this.year);
            if (!this.year.endsWith("年")) {
                buf.append("年");
            }
        }
        if (null != this.month) {
            buf.append(this.month);
            if (!this.month.endsWith("月")) {
                buf.append("月");
            }
        }
        if (null != this.date) {
            buf.append(this.date);
            if (!this.date.endsWith("日")) {
                buf.append("日");
            }
        }
        return buf.toString();
    }

    /**
     * 格式化简化日期。
     *
     * <p>省略年份为当前年时的前缀，用于图轴展示。</p>
     *
     * @return 简化后的日期文本。
     */
    public String formatSimpleDate() {
        StringBuilder buf = new StringBuilder();
        if (null != this.year) {
            buf.append(this.year.replace("年", ""));
        }
        if (null != this.month) {
            buf.append("-");
            buf.append(this.month.replace("月", ""));
        }
        if (null != this.date) {
            buf.append("-");
            buf.append(this.date.replace("日", ""));
        }
        return buf.toString();
    }

    /**
     * 计算有多少个输入词与标签匹配。
     * @param words 词列表。
     * @return 计算结果。
     */
    public int numMatchingLabels(List<String> words) {
        int num = 0;
        String[] labels = this.label.split(",");

        for (String label : labels) {
            for (String word : words) {
                if (word.equals(label.trim())) {
                    ++num;
                }
            }
        }

        this.currentLabelMatchingNum = num;
        return num;
    }

    /**
     * 按标签与日期判断相等。
     *
     * @param obj 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Atom) {
            Atom other = (Atom) obj;
            if (other.label.equals(this.label)) {
                boolean year = false;
                if (null != other.year && null != this.year
                    && other.year.equals(this.year)) {
                    year = true;
                }
                else if (null == other.year && null == this.year) {
                    year = true;
                }

                boolean month = false;
                if (null != other.month && null != this.month
                    && other.month.equals(this.month)) {
                    month = true;
                }
                else if (null == other.month && null == this.month) {
                    month = true;
                }

                boolean date = false;
                if (null != other.date && null != this.date
                    && other.date.equals(this.date)) {
                    date = true;
                }
                else if (null == other.date && null == this.date) {
                    date = true;
                }

                return year && month && date;
            }
        }

        return false;
    }

    /**
     * 按标签与日期计算哈希值。
     *
     * @return 哈希值。
     */
    @Override
    public int hashCode() {
        int code = this.label.hashCode();
        if (null != this.year) {
            code += this.year.hashCode() * 7;
        }
        if (null != this.month) {
            code += this.month.hashCode() * 7;
        }
        if (null != this.date) {
            code += this.date.hashCode() * 7;
        }
        return code;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("label", this.label);
        json.put("year", this.year);
        json.put("month", this.month);
        json.put("date", this.date);
        json.put("value", this.value);
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

    /**
     * 校验原子数据的标签是否有效。
     *
     * @param atom 原子数据。
     * @return 标签非空返回 {@code true} 。
     */
    public static boolean checkLabel(Atom atom) {
        String[] array = atom.label.split(",");
        if (array.length < 3) {
            return false;
        }

        return true;
    }
}
