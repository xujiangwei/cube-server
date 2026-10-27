/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.common.JSONable;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class Palette implements JSONable {

    /** 黑色。 */
    public final static String Black = "black";
    /** 灰色。 */
    public final static String Gray = "gray";
    /** 白色。 */
    public final static String White = "white";
    /** 红色。 */
    public final static String Red = "red";
    /** 橙色。 */
    public final static String Orange = "orange";
    /** 黄色。 */
    public final static String Yellow = "yellow";
    /** 绿色。 */
    public final static String Green = "green";
    /** 青色。 */
    public final static String Cyan = "cyan";
    /** 蓝色。 */
    public final static String Blue = "blue";
    /** 紫色。 */
    public final static String Purple = "purple";

    private List<ColorScore> scores = new ArrayList<>();

    public Palette() {
    }

    public Palette(JSONObject json) {
        for (String key : json.keySet()) {
            if (Black.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Black, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (Gray.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Gray, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (White.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(White, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (Red.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Red, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (Orange.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Orange, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (Yellow.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Yellow, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (Green.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Green, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (Cyan.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Cyan, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (Blue.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Blue, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            } else if (Purple.equalsIgnoreCase(key)) {
                ColorScore score = new ColorScore(Purple, Double.parseDouble(json.get(key).toString()));
                this.scores.add(score);
            }
        }

        Collections.sort(this.scores, new Comparator<ColorScore>() {
            @Override
            public int compare(ColorScore cs1, ColorScore cs2) {
                return (int) Math.round((cs2.score - cs1.score) * 1000.0);
            }
        });
    }

    /**
     * 判断是否空。
     *
     * @return 空。
     */
    public boolean isEmpty() {
        return this.scores.isEmpty();
    }
    
    /**
     * 判断是否非空。
     *
     * @return 非空。
     */
    public boolean isNotEmpty() {
        return !this.scores.isEmpty();
    }

    /**
     * 获取基础色分数。
     *
     * @return 基础色分数。
     */
    public ColorScore getPrimitiveColorScore() {
        if (this.scores.isEmpty()) {
            return null;
        }
        return this.scores.get(0);
    }

    /**
     * 获取次要颜色分数。
     *
     * @return 次要颜色分数。
     */
    public ColorScore getSecondaryColorScore() {
        if (this.scores.size() >= 2) {
            return this.scores.get(1);
        }
        return null;
    }

    /**
     * 获取第三颜色分数。
     *
     * @return 第三颜色分数。
     */
    public ColorScore getTertiaryColorScore() {
        if (this.scores.size() >= 3) {
            return this.scores.get(2);
        }
        return null;
    }

    /**
     * 获取第四色分数。
     *
     * @return 第四色分数。
     */
    public ColorScore getFourthColorScore() {
        if (this.scores.size() >= 4) {
            return this.scores.get(3);
        }
        return null;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        for (ColorScore cs : this.scores) {
            json.put(cs.color, cs.score);
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
        return this.toJSON();
    }

    public class ColorScore {

        public final String color;

        public final double score;

        public ColorScore(String color, double score) {
            this.color = color;
            this.score = score;
        }
    }
}
