/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

/**
 * 行访问器：逐行消费查询结果，不产出集合。
 *
 * <p>适用于导出、统计、清理这类"边读边处理"的场景——结果集不会被整体物化进内存。</p>
 *
 * <p><b>约束</b>：{@code values} 数组由存储实现复用，回调返回后内容会被下一行覆盖；
 * 需要保留行数据必须自行 {@code clone()}。另外，遍历期间当前连接被本次查询独占，
 * 回调里<b>禁止</b>再对同一个存储发起查询。</p>
 */
@FunctionalInterface
public interface RowVisitor {

    /**
     * 访问一行。
     *
     * @param values 当前行的值，长度等于 {@code columns.size()}，元素可能为 {@code null}。
     * @param columns 列索引。
     * @return 返回 {@code false} 终止遍历（用于"找到即停"）。
     */
    boolean visit(Object[] values, ColumnIndex columns);
}
