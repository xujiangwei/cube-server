/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 处理结果。
 */
public class HookResult {

    private List<HookResult> results;

    private Map<String, Object> data;

    public HookResult() {
        this.results = new ArrayList<>();
    }

    /**
     * 合并另一份结果。
     *
     * <p>同名键以参数结果为准，其余键保留。</p>
     *
     * @param result 待合并结果。
     */
    public void add(HookResult result) {
        this.results.add(result);
    }

    /**
     * 写入结果项。
     *
     * @param key   键。
     * @param value 值。
     */
    public void set(String key, Object value) {
        if (null == this.data) {
            this.data = new HashMap<>();
        }
        this.data.put(key, value);
    }

    /**
     * 读取结果项。
     *
     * @param key 键。
     * @return 值；不存在返回 {@code null} 。
     */
    public Object get(String key) {
        if (null != this.data) {
            Object value = this.data.get(key);
            if (null != value) {
                return value;
            }
        }

        for (HookResult hr : this.results) {
            Object value = hr.get(key);
            if (null != value) {
                return value;
            }
        }

        return null;
    }

    /**
     * 读取布尔结果项。
     *
     * @param key 键。
     * @return 为真返回 {@code true} ；不存在或类型不符返回 {@code false} 。
     */
    public boolean getBoolean(String key) {
        Object value = this.get(key);
        if (null == value) {
            return false;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        else {
            return false;
        }
    }
}
