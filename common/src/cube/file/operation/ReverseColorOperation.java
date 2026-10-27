/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.file.ImageOperation;
import org.json.JSONObject;

/**
 * 反转颜色。
 */
public class ReverseColorOperation extends ImageOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "ReverseColor";

    public ReverseColorOperation() {
    }

    public ReverseColorOperation(JSONObject json) {
        super(json);
    }

    /**
     * 获取操作名称。
     *
     * @return 固定返回 {@link #Operation} 。
     */
    @Override
    public String getOperation() {
        return Operation;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        return json;
    }
}
