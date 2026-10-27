/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 可变生成记录。
 *
 * <p>用于在流式生成过程中逐步累积内容的中间对象。</p>
 */
public class MutableGeneratingRecord {

    private GeneratingRecord value;

    public MutableGeneratingRecord() {
    }

    public MutableGeneratingRecord(GeneratingRecord value) {
        this.value = value;
    }

    /**
     * 设置值。
     *
     * @param value 值。
     */
    public void setValue(GeneratingRecord value) {
        this.value = value;
    }

    /**
     * 获取值。
     *
     * @return 值。
     */
    public GeneratingRecord getValue() {
        return this.value;
    }

    /**
     * 判断是否空。
     *
     * @return 空。
     */
    public boolean isNull() {
        return (null == this.value);
    }

    /**
     * 判断是否非空。
     *
     * @return 非空。
     */
    public boolean isNotNull() {
        return (null != this.value);
    }
}
