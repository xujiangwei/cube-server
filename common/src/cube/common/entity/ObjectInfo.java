/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2020-2024 Ambrose Xu.
 */

package cube.common.entity;

import cube.util.TimeDuration;
import cube.util.TimeUtils;
import cube.vision.Size;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 物体信息。
 */
public class ObjectInfo extends Entity {

    private String fileCode;

    private FileLabel fileLabel;

    private long elapsed;

    private Size size;

    private List<Material> materials;

    private String description;

    public ObjectInfo(JSONObject json) {
        this.fileCode = json.getString("fileCode");
        this.elapsed = json.getLong("elapsed");
        this.size = new Size(json.getJSONObject("size"));
        this.materials = new ArrayList<>();
        JSONArray array = json.getJSONArray("materials");
        for (int i = 0; i < array.length(); ++i) {
            this.materials.add(new Material(array.getJSONObject(i)));
        }

        if (json.has("fileLabel")) {
            this.fileLabel = new FileLabel(json.getJSONObject("fileLabel"));
        }

        if (json.has("description")) {
            this.description = json.getString("description");
        }
        else {
            this.description = this.makeDescription(this.materials);
        }
    }

    /**
     * 获取文件码。
     *
     * @return 文件码。
     */
    public String getFileCode() {
        return this.fileCode;
    }

    /**
     * 获取耗时。
     *
     * @return 耗时。
     */
    public long getElapsed() {
        return this.elapsed;
    }

    /**
     * 获取大小。
     *
     * @return 大小。
     */
    public Size getSize() {
        return this.size;
    }

    /**
     * 获取对象列表。
     *
     * @return 对象列表。
     */
    public List<Material> getObjects() {
        return this.materials;
    }

    /**
     * 获取描述。
     *
     * @return 描述。
     */
    public String getDescription() {
        return this.description;
    }

    /**
     * 获取文件标签。
     *
     * @return 文件标签。
     */
    public FileLabel getFileLabel() {
        return this.fileLabel;
    }

    /**
     * 设置文件标签。
     *
     * @param fileLabel 文件标签。
     */
    public void setFileLabel(FileLabel fileLabel) {
        this.fileLabel = fileLabel;
    }

    /**
     * 生成 Markdown 格式的描述。
     * @param list list。
     * @return 字符串结果；无值时返回 {@code null} 。
     */
    private String makeDescription(List<Material> list) {
        StringBuilder buf = new StringBuilder();
        buf.append("图片中包含以下物品：\n\n");
        for (Material material : list) {
            String prob = String.format("%.2f", material.prob * 100);
            buf.append("- ").append(material.label);
            buf.append(" <font color=\"").append(material.color).append("\">").append("●</font>");
            buf.append(" *(").append(prob).append("%)*\n");
        }
        buf.append("\n");
        TimeDuration timeDuration = TimeUtils.calcTimeDuration(this.elapsed);
        buf.append("识别耗时**").append(timeDuration.toHumanString()).append("**，共检测出**")
                .append(materials.size()).append("**件物品。");
        return buf.toString();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("fileCode", this.fileCode);
        json.put("elapsed", this.elapsed);
        json.put("size", this.size.toJSON());
        JSONArray array = new JSONArray();
        for (Material material : this.materials) {
            array.put(material.toJSON());
        }
        json.put("materials", array);

        if (null != this.fileLabel) {
            json.put("fileLabel", this.fileLabel.toJSON());
        }

        if (null != this.description) {
            json.put("description", this.description);
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
}
