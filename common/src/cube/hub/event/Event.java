/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cell.util.Utils;
import cube.common.JSONable;
import cube.common.entity.ClientDescription;
import cube.common.entity.FileLabel;
import cube.hub.Product;
import org.json.JSONObject;

import java.io.File;

/**
 * 事件描述。
 */
public abstract class Event implements JSONable {

    private long sn;

    private String code;

    /** 所属产品。 */
    protected Product product;

    /** 名称。 */
    protected String name;

    /** 文件。 */
    protected File file;

    /** 文件标签。 */
    protected FileLabel fileLabel;

    /** 描述。 */
    protected ClientDescription description;

    public Event(Product product, String name) {
        this.product = product;
        this.sn = Utils.generateSerialNumber();
        this.name = name;
    }

    public Event(Product product, String name, File file) {
        this.product = product;
        this.sn = Utils.generateSerialNumber();
        this.name = name;
        this.file = file;
    }

    public Event(Product product, long sn, String name) {
        this.product = product;
        this.sn = sn;
        this.name = name;
    }

    public Event(Product product, long sn, String name, File file) {
        this.product = product;
        this.sn = sn;
        this.name = name;
        this.file = file;
    }

    public Event(JSONObject json) {
        this.product = Product.parse(json.getString("product"));
        this.sn = json.getLong("sn");
        this.name = json.getString("name");

        if (json.has("code")) {
            this.code = json.getString("code");
        }

        if (json.has("fileLabel")) {
            this.fileLabel = new FileLabel(json.getJSONObject("fileLabel"));
        }

        if (json.has("description")) {
            this.description = new ClientDescription(json.getJSONObject("description"));
        }
    }

    /**
     * 获取事件序列号。
     *
     * @return 序列号。
     */
    public long getSerialNumber() {
        return this.sn;
    }

    /**
     * 设置码。
     *
     * @param code 码。
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * 获取码。
     *
     * @return 码。
     */
    public String getCode() {
        return this.code;
    }

    /**
     * 获取所属产品。
     *
     * @return 产品。
     */
    public Product getProduct() {
        return this.product;
    }

    /**
     * 获取名称。
     *
     * @return 名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取文件。
     *
     * @return 文件。
     */
    public File getFile() {
        return this.file;
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
     * 获取文件标签。
     *
     * @return 文件标签。
     */
    public FileLabel getFileLabel() {
        return this.fileLabel;
    }

    /**
     * 设置描述。
     *
     * @param description 描述。
     */
    public void setDescription(ClientDescription description) {
        this.description = description;
    }

    /**
     * 获取描述。
     *
     * @return 描述。
     */
    public ClientDescription getDescription() {
        return this.description;
    }

    /**
     * 获取冒充者 ID。
     *
     * @return 冒充者 ID。
     */
    public Long getPretenderId() {
        return (null != this.description) ? this.description.getPretender().getId() : null;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("product", this.product.name);
        json.put("sn", this.sn);
        json.put("name", this.name);

        if (null != this.code) {
            json.put("code", this.code);
        }

        if (null != this.fileLabel) {
            json.put("fileLabel", this.fileLabel.toJSON());
        }

        if (null != this.description) {
            json.put("description", this.description.toCompactJSON());
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
        JSONObject json = new JSONObject();
        json.put("product", this.product.name);
        json.put("sn", this.sn);
        json.put("name", this.name);

        if (null != this.code) {
            json.put("code", this.code);
        }

        if (null != this.fileLabel) {
            json.put("fileLabel", this.fileLabel.toCompactJSON());
        }

        return json;
    }
}
