/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2026 Ambrose Xu.
 */

package cube.ferry;

import cube.common.JSONable;
import org.json.JSONObject;

public class GnosisAgent implements JSONable {

    /** Gnosis 动作：生命体征。 */
    public final static String VitalSigns = "Vital-Signs";

    /** Gnosis 动作：人员。 */
    public final static String Persons = "Persons";

    /** 名称。 */
    public final String name;

    /** 是否使用模拟数据。 */
    public final boolean mock;

    /** 结果。 */
    public JSONObject result;

    public GnosisAgent(String name, boolean mock) {
        this.name = name;
        this.mock = mock;
    }

    public GnosisAgent(JSONObject json) {
        this.name = json.getString("name");
        this.mock = json.getBoolean("mock");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        json.put("mock", this.mock);
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
