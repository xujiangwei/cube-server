/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology;

import cube.common.JSONable;
import cube.common.Language;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 报告属性。
 */
public class Attribute implements JSONable {

    /** 最大年龄。 */
    public final static int MAX_AGE = 65;

    /** 最小年龄。 */
    public final static int MIN_AGE = 6;

    /** 性别。 */
    public final String gender;

    /** 年龄。 */
    public final int age;

    /** 角色。 */
    public final String role;

    /** 语言。 */
    public final Language language;

    /** 是否严格。 */
    public final boolean strict;

    public Attribute(String gender, int age, String role, Language language, boolean strict) {
        this.gender = gender.equals("男") ? "male" : (gender.equals("女") ? "female" : gender.toLowerCase());
        this.age = age;
        this.role = role;
        this.language = language;
        this.strict = strict;
    }

    public Attribute(String gender, int age, String role, Language language) {
        this.gender = gender.equals("男") ? "male" : (gender.equals("女") ? "female" : gender.toLowerCase());
        this.age = age;
        this.role = role;
        this.language = language;
        this.strict = false;
    }

    public Attribute(String gender, int age, String role) {
        this.gender = gender.equals("男") ? "male" : (gender.equals("女") ? "female" : gender.toLowerCase());
        this.age = age;
        this.role = role;
        this.language = Language.Chinese;
        this.strict = false;
    }

    public Attribute(JSONObject json) {
        this.gender = json.getString("gender");
        this.age = json.getInt("age");
        this.role = json.has("role") ? json.getString("role") : "";
        this.language = json.has("language") ? Language.parse(json.getString("language")) : Language.Chinese;
        this.strict = json.has("strict") && json.getBoolean("strict");
    }

    /**
     * 判断是否男。
     *
     * @return 男。
     */
    public boolean isMale() {
        return this.gender.equalsIgnoreCase("male")
                || this.gender.contains("男");
    }

    /**
     * 判断是否女。
     *
     * @return 女。
     */
    public boolean isFemale() {
        return this.gender.equalsIgnoreCase("female")
                || this.gender.contains("女");
    }

    /**
     * 获取性别文本。
     *
     * @return 性别文本。
     */
    public String getGenderText() {
        if (Language.Chinese == this.language) {
            return this.isMale() ? "男" : "女";
        }
        else {
            return this.isMale() ? "Male" : "Female";
        }
    }

    /**
     * 获取年龄文本。
     *
     * @return 年龄文本。
     */
    public String getAgeText() {
        if (Language.Chinese == this.language) {
            return this.age + "岁";
        }
        else {
            return this.age + " years";
        }
    }

    /**
     * 计算因子。
     *
     * @return 因子。
     */
    public double[] calcFactor() {
        double[] data = new double[] {
                this.isMale() ? 0.9 : 0.1,
                ((double) this.age) * 0.01
        };
        return data;
    }

    /**
     * 判断是否有效。
     *
     * @return 有效。
     */
    public boolean isValid() {
        return this.age > 0 && this.gender.length() > 0;
    }

    /**
     * 把属性因子转为数组。
     *
     * @return 因子数组。
     */
    public JSONArray calcFactorToArray() {
        double[] data = this.calcFactor();
        JSONArray array = new JSONArray();
        array.put(data[0]);
        array.put(data[1]);
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
        json.put("gender", this.gender);
        json.put("age", this.age);
        json.put("role", this.role);
        json.put("language", this.language.simplified);
        json.put("strict", this.strict);
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
