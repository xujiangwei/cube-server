/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.common.entity.DetectedObject;
import cube.file.ImageOperation;
import org.json.JSONObject;

import java.util.List;

/**
 * 检测图像里的对象操作。
 */
public class DetectObjectsOperation extends ImageOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "detectObject";

    private List<DetectedObject> detectedObjectList;

    public DetectObjectsOperation() {
        super();
    }

    public DetectObjectsOperation(JSONObject json) {

    }

    /**
     * 设置检测到的目标列表。
     *
     * <p>用于把识别结果回填到操作对象，便于随工作流一并序列化。</p>
     *
     * @param detectedObjects 目标列表。
     */
    public void setDetectedObjects(List<DetectedObject> detectedObjects) {
        this.detectedObjectList = detectedObjects;
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
