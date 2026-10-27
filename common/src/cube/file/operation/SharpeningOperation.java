/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.file.ImageOperation;
import org.json.JSONObject;

/**
 * 锐化操作。
 */
public class SharpeningOperation extends ImageOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "Sharpening";

    private double sigma;

    public SharpeningOperation(double sigma) {
        this.sigma = sigma;
    }

    public SharpeningOperation(JSONObject json) {
        super(json);
        this.sigma = json.getDouble("sigma");
    }

    /**
     * 获取锐化强度。
     *
     * @return sigma 值，越大锐化越明显。
     */
    public double getSigma() {
        return this.sigma;
    }

    /**
     * 把锐化强度格式化为字符串。
     *
     * <p>用于拼接处理命令，避免浮点数在不同平台上出现多余的尾随零。</p>
     *
     * @return 格式化后的 sigma 值。
     */
    public String formatSigma() {
        return String.format("%.1f", this.sigma);
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
        json.put("sigma", this.sigma);
        return json;
    }
}
