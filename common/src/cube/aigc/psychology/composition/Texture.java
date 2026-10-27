/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.common.JSONable;
import org.json.JSONObject;

public class Texture implements JSONable {

    /** 最大。 */
    public double max;

    /** 平均。 */
    public double avg;

    /** 平方偏差。 */
    public double squareDeviation;

    /** 标准偏差。 */
    public double standardDeviation;

    /** 层次。 */
    public double hierarchy;

    /** 密度。 */
    public double density;

    public Texture() {
        this.max = 0;
        this.avg = 0;
        this.squareDeviation = 0;
        this.standardDeviation = 0;
        this.hierarchy = 0;
        this.density = 1;
    }

    public Texture(double max, double avg, double squareDeviation, double standardDeviation,
                   double hierarchy, double density) {
        this.max = max;
        this.avg = avg;
        this.squareDeviation = squareDeviation;
        this.standardDeviation = standardDeviation;
        this.hierarchy = hierarchy;
        this.density = density;
    }

    public Texture(JSONObject json) {
        this.max = Double.parseDouble(json.getString("max"));
        this.avg = Double.parseDouble(json.getString("avg"));
        this.squareDeviation = Double.parseDouble(json.getString("squareDeviation"));
        this.standardDeviation = Double.parseDouble(json.getString("standardDeviation"));
        this.hierarchy = Double.parseDouble(json.getString("hierarchy"));
        this.density = Double.parseDouble(json.getString("density"));
    }

    /**
     * 判断是否有效。
     *
     * @return 有效。
     */
    public boolean isValid() {
        return this.max > 0 && this.avg > 0 && this.density > 0.0014d;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("max", Double.toString(this.max));
        json.put("avg", Double.toString(this.avg));
        json.put("squareDeviation", Double.toString(this.squareDeviation));
        json.put("standardDeviation", Double.toString(this.standardDeviation));
        json.put("hierarchy", Double.toString(this.hierarchy));
        json.put("density", Double.toString(this.density));
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
