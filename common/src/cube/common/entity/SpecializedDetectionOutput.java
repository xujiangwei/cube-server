/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 结构化的检测结果，包含成功的空检测结果与视频流快照。
 */
public final class SpecializedDetectionOutput implements JSONable {
    /**
     * 结果序号。
     */
    public final long sn;
    /**
     * 检测结果载荷。
     */
    public final JSONObject resultPayload;
    /**
     * 带有单元名与时间戳的完整结果 JSON。
     */
    private final JSONObject json;

    /**
     * 构造方法。
     *
     * @param sn      结果序号
     * @param payload Unit 返回的结果载荷，须包含 response、thought、resultPayload 与 performance
     * @throws IllegalArgumentException 当结果载荷缺少上述必需字段时抛出
     */
    public SpecializedDetectionOutput(long sn, JSONObject payload) {
        if (!(payload.opt("response") instanceof String) || !(payload.opt("thought") instanceof String)
                || payload.optJSONObject("resultPayload") == null || payload.optJSONObject("performance") == null) {
            throw new IllegalArgumentException("Invalid detection response shape");
        }
        this.sn = sn;
        this.json = new JSONObject(payload.toString()).put("sn", sn).put("unit", SpecializedDetectionInput.UNIT)
                .put("timestamp", System.currentTimeMillis());
        this.resultPayload = this.json.getJSONObject("resultPayload");
    }

    /**
     * 输出完整结果 JSON 的副本。
     *
     * @return 结果 JSON
     */
    @Override
    public JSONObject toJSON() {
        return new JSONObject(json.toString());
    }

    /**
     * 输出紧凑结果 JSON，与 {@link #toJSON()} 一致。
     *
     * @return 结果 JSON
     */
    @Override
    public JSONObject toCompactJSON() {
        return toJSON();
    }
}
