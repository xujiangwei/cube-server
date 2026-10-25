/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

/**
 * 行映射器：把一行数据直接映射成业务对象。
 *
 * <p>与 {@link Storage#executeQuery} 的差别是<b>不产生中间的 {@link StorageField}</b>：
 * 值已经按声明类型取好（LONG → {@code Long}、INT → {@code Integer}、BOOL → {@code Boolean}、
 * STRING → {@code String}），SQL {@code NULL} 保持为 {@code null} 。</p>
 *
 * <p><b>约束</b>：{@code values} 数组由存储实现复用，回调返回后内容会被下一行覆盖。
 * 需要保留行数据必须自行 {@code clone()}。</p>
 *
 * @param <T> 映射结果类型。
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * 映射一行。
     *
     * @param values 当前行的值，长度等于 {@code columns.size()}，元素可能为 {@code null}。
     * @param columns 列索引，用于按列名定位。
     * @return 映射结果。
     */
    T map(Object[] values, ColumnIndex columns);
}
