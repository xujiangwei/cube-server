/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 模型的应用配置。
 */
public class ModelConfig implements JSONable {

    /**
     * 超长上下文上限，128 KB 。
     */
    public static int EXTRA_LONG_CONTEXT_LIMIT = 128 * 1024;

    /**
     * Baize 模型的上下文字符上限，40 KB 。
     */
    public static int BAIZE_CONTEXT_LIMIT = 40 * 1024;

    /**
     * Baize2 模型的上下文字符上限，256 KB 。
     */
    public static int BAIZE_2_CONTEXT_LIMIT = 256 * 1024;

    /**
     * 文生图单元名称列表。
     */
    public final static String[] TEXT_TO_IMAGE_UNIT = new String[]{"DallE"};

    /**
     * 默认文本生成单元名。
     */
    public final static String BAIZE_UNIT = "Baize";

    /**
     * 第二代文本生成单元名。
     */
    public final static String BAIZE_2_UNIT = "Baize2";

    /**
     * 面部表情识别单元名。
     */
    public final static String FACIAL_EXPRESSION_UNIT = "FacialExpression";

    private final String model;

    private final String name;

    private final String desc;

    private final String apiURL;

    private final JSONObject parameter;

    public ModelConfig(String model, String name, String desc, String apiURL, JSONObject parameter) {
        this.model = model;
        this.name = name;
        this.desc = desc;
        this.apiURL = apiURL + (apiURL.endsWith("/") ? "" : "/");
        this.parameter = parameter;
    }

    public ModelConfig(String model, JSONObject json) {
        this.model = model;
        this.name = json.getString("name");
        this.desc = json.getString("desc");
        this.apiURL = json.getString("apiURL");
        this.parameter = json.getJSONObject("parameter");
    }

    public ModelConfig(JSONObject json) {
        this.model = json.has("model") ? json.getString("model") :
                json.getJSONObject("parameter").getString("unit");
        this.name = json.getString("name");
        this.desc = json.getString("desc");
        this.apiURL = json.getString("apiURL");
        this.parameter = json.getJSONObject("parameter");
    }

    /**
     * 获取模型标识。
     *
     * @return 模型标识。
     */
    public String getModel() {
        return this.model;
    }

    /**
     * 获取配置名称。
     *
     * @return 名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取配置描述。
     *
     * @return 描述。
     */
    public String getDesc() {
        return this.desc;
    }

    /**
     * 获取模型接口地址。
     *
     * @return 接口地址。
     */
    public String getApiURL() {
        return this.apiURL;
    }

    /**
     * 获取执行该模型的单元名。
     *
     * @return 单元名。
     */
    public String getUnitName() {
        return this.parameter.getString("unit");
    }

    /**
     * 获取流式通道地址。
     *
     * @return 通道地址。
     */
    public String getChannelURL() {
        int index = this.apiURL.indexOf("/aigc/");
        return this.apiURL.substring(0, index) + "/aigc/channel/";
    }

    /**
     * 获取模型参数。
     *
     * @return 参数对象；未配置时为空对象。
     */
    public JSONObject getParameter() {
        return this.parameter;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("model", this.model);
        json.put("name", this.name);
        json.put("desc", this.desc);
        json.put("apiURL", this.apiURL);
        json.put("parameter", this.parameter);
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
     * 判断指定的单元是否是 TextToImage 单元。
     *
     * @param unitName unitName。
     * @return 处理结果。
     */
    public static boolean isTextToImageUnit(String unitName) {
        for (String name : TEXT_TO_IMAGE_UNIT) {
            if (name.equalsIgnoreCase(unitName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取提示词长度限制。
     *
     * @param unitName unitName。
     * @return 处理结果。
     */
    public static int getPromptLengthLimit(String unitName) {
        if (unitName.equalsIgnoreCase(BAIZE_UNIT)) {
            return BAIZE_CONTEXT_LIMIT;
        } else if (unitName.equalsIgnoreCase(BAIZE_2_UNIT)) {
            return BAIZE_2_CONTEXT_LIMIT;
        }

        return EXTRA_LONG_CONTEXT_LIMIT;
    }
}
