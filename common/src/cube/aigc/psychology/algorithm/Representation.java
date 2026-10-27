/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

import cube.aigc.psychology.Resource;
import cube.aigc.psychology.material.Thing;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 表征含义。
 */
public class Representation implements JSONable {

    /** 高 Trick。 */
    public final static String HighTrick = "明显";
    /** 低 Trick。 */
    public final static String LowTrick = "缺乏"; // "不足";
    /** 常规 Trick。 */
    public final static String NormalTrick = "具有";

    /** 高倾向。 */
    public final static String HighTendency = "倾向高";
    /** 低倾向。 */
    public final static String LowTendency = "倾向低";
    /** 正常倾向。 */
    public final static String NormalTendency = "倾向中等";

    /** 强。 */
    public final static String Strong = "强";
    /** 弱。 */
    public final static String Weak = "弱";
    /** 正常。 */
    public final static String Normal = "一般";
    /** 高。 */
    public final static String High = "高";
    /** 低。 */
    public final static String Low = "低";

    /** 后缀。 */
    public final static String Suffix = "的";

    /** 知识策略。 */
    public KnowledgeStrategy knowledgeStrategy;

    /** 正相关。 */
    public int positiveCorrelation = 0;

    /** 负相关。 */
    public int negativeCorrelation = 0;

    /** 描述。 */
    public String description = "";

    /** 物件。 */
    public List<PerceptronThing> things;

    public Representation(KnowledgeStrategy knowledgeStrategy) {
        this.knowledgeStrategy = knowledgeStrategy;
        this.things = new ArrayList<>();
    }

    public Representation(JSONObject json) {
        this.knowledgeStrategy = new KnowledgeStrategy(json.getJSONObject("knowledgeStrategy"));
        if (this.knowledgeStrategy.isEmpty()) {
            // 重新从资源中加载数据
            KnowledgeStrategy ks = Resource.getInstance().getTermInterpretation(this.knowledgeStrategy.getTerm());
            if (null != ks) {
                this.knowledgeStrategy = ks;
            }
        }
        this.positiveCorrelation = json.getInt("positiveCorrelation");
        this.negativeCorrelation = json.getInt("negativeCorrelation");
        this.description = json.getString("description");
        this.things = new ArrayList<>();
        if (json.has("things")) {
            JSONArray thingArray = json.getJSONArray("things");
            for (int i = 0; i < thingArray.length(); ++i) {
                PerceptronThing thing = new PerceptronThing(thingArray.getJSONObject(i));
                this.things.add(thing);
            }
        }
    }

    /**
     * 添加物件。
     *
     * @param things 物件。
     */
    public void addThings(Thing[] things) {
        if (null == things || things.length == 0) {
            return;
        }
        for (Thing thing : things) {
            PerceptronThing pt = new PerceptronThing(thing);
            this.things.add(pt);
        }
    }

    /**
     * 构造描述。
     *
     * @return 描述。
     */
    public void makeDescription() {
        String marked = null;

        if (this.negativeCorrelation > 0 &&
                this.positiveCorrelation < this.negativeCorrelation) {
            switch (this.knowledgeStrategy.getTerm()) {
                case Depression:
                case Anxiety:
                case Suspiciousness:
                    marked = this.knowledgeStrategy.getTerm().word + LowTendency;
                    break;
                case SenseOfReality:
                case SenseOfSecurity:
                    marked = this.knowledgeStrategy.getTerm().word + Weak;
                    break;
                case SelfEsteem:
                    marked = this.knowledgeStrategy.getTerm().word + Normal;
                    break;
                case Instinct:
                    marked = this.knowledgeStrategy.getTerm().word + Suffix;
                    break;
                default:
                    marked = LowTrick + this.knowledgeStrategy.getTerm().word;
                    break;
            }
        }
        else if (this.positiveCorrelation >= 2 ||
                (this.positiveCorrelation - this.negativeCorrelation) >= 4) {
            switch (this.knowledgeStrategy.getTerm()) {
                case Depression:
                case Anxiety:
                case Suspiciousness:
                    marked = this.knowledgeStrategy.getTerm().word + HighTendency;
                    break;
                case SenseOfReality:
                case SenseOfSecurity:
                    marked = this.knowledgeStrategy.getTerm().word + Strong;
                    break;
                case SelfEsteem:
                    marked = this.knowledgeStrategy.getTerm().word + High;
                    break;
                case Instinct:
                    marked = this.knowledgeStrategy.getTerm().word + Suffix;
                    break;
                default:
                    marked = HighTrick + this.knowledgeStrategy.getTerm().word;
                    break;
            }
        }
        else {
            switch (this.knowledgeStrategy.getTerm()) {
                case Depression:
                case Anxiety:
                case Suspiciousness:
                    marked = this.knowledgeStrategy.getTerm().word + NormalTendency;
                    break;
                case SenseOfReality:
                case SenseOfSecurity:
                    marked = this.knowledgeStrategy.getTerm().word + Normal;
                    break;
                case SelfEsteem:
                    marked = this.knowledgeStrategy.getTerm().word + Normal;
                    break;
                case Instinct:
                    marked = this.knowledgeStrategy.getTerm().word + Suffix;
                    break;
                default:
                    marked = NormalTrick + this.knowledgeStrategy.getTerm().word;
                    break;
            }
        }
        // 设置短描述
        this.description = marked;
    }

    /**
     * 判断指定对象是否与本对象相等。
     *
     * @param obj 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Representation) {
            Representation other = (Representation) obj;
            if (other.knowledgeStrategy.getTerm() == this.knowledgeStrategy.getTerm()) {
                return true;
            }
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
        return this.knowledgeStrategy.getTerm().hashCode();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("knowledgeStrategy", this.knowledgeStrategy.toJSON());
        json.put("positiveCorrelation", this.positiveCorrelation);
        json.put("negativeCorrelation", this.negativeCorrelation);
        json.put("description", this.description);
        JSONArray array = new JSONArray();
        for (PerceptronThing thing : this.things) {
            array.put(thing.toJSON());
        }
        json.put("things", array);
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
        JSONObject json = new JSONObject();
        json.put("knowledgeStrategy", this.knowledgeStrategy.toCompactJSON());
        json.put("positiveCorrelation", this.positiveCorrelation);
        json.put("negativeCorrelation", this.negativeCorrelation);
        json.put("description", this.description);
        JSONArray array = new JSONArray();
        for (PerceptronThing thing : this.things) {
            array.put(thing.toCompactJSON());
        }
        json.put("things", array);
        return json;
    }

    /**
     * 转换为是否严格 JSON。
     *
     * @return 是否严格 JSON。
     */
    public JSONObject toStrictJSON() {
        JSONObject json = new JSONObject();
        json.put("knowledgeStrategy", this.knowledgeStrategy.toCompactJSON());
        json.put("positiveCorrelation", this.positiveCorrelation);
        json.put("negativeCorrelation", this.negativeCorrelation);
        json.put("description", this.description);
        JSONArray array = new JSONArray();
        for (PerceptronThing thing : this.things) {
            array.put(thing.toJSON());
        }
        json.put("things", array);
        return json;
    }
}
