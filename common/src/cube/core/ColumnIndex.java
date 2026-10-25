/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 列名到列下标的映射。
 *
 * <p>作用范围是<b>一次查询</b>：在 {@link Storage#forEachRow} / {@link Storage#queryList}
 * 开始前构建一次，回调里用 {@link #indexOf(String)} 换出下标后缓存到局部变量，不要每行重复查名。</p>
 *
 * <p>列顺序由传入的 {@link StorageField} 数组（或 {@link ResultSetMetaData}）决定。
 * {@code fields == null}（{@code SELECT *}）时列顺序取决于表结构，
 * 此时<b>禁止</b>把下标写成常量。</p>
 */
public final class ColumnIndex {

    private final String[] names;

    private final Map<String, Integer> positions;

    private ColumnIndex(String[] names) {
        this.names = names;
        this.positions = new HashMap<>(Math.max(4, names.length * 2));
        for (int i = 0; i < names.length; ++i) {
            this.positions.put(names[i], i);
        }
    }

    /**
     * 按字段定义构建。
     *
     * @param fields 字段数组，允许为 {@code null} 或空数组。
     * @return 列索引。
     */
    public static ColumnIndex of(StorageField[] fields) {
        if (null == fields || 0 == fields.length) {
            return new ColumnIndex(new String[0]);
        }

        String[] names = new String[fields.length];
        for (int i = 0; i < fields.length; ++i) {
            names[i] = fields[i].getName();
        }

        return new ColumnIndex(names);
    }

    /**
     * 按列名数组构建。
     *
     * @param names 列名数组，允许为 {@code null} 。
     * @return 列索引。
     */
    public static ColumnIndex of(String[] names) {
        if (null == names || 0 == names.length) {
            return new ColumnIndex(new String[0]);
        }

        return new ColumnIndex(names.clone());
    }

    /**
     * 按结果集元数据构建，用于 {@code SELECT *} 场景。
     *
     * @param metaData 结果集元数据。
     * @return 列索引。
     * @throws SQLException 读取元数据失败时抛出。
     */
    public static ColumnIndex of(ResultSetMetaData metaData) throws SQLException {
        if (null == metaData) {
            return new ColumnIndex(new String[0]);
        }

        int count = metaData.getColumnCount();
        String[] names = new String[count];
        for (int i = 0; i < count; ++i) {
            // 用 label：有别名时得到的是别名，与调用方的预期一致
            names[i] = metaData.getColumnLabel(i + 1);
        }

        return new ColumnIndex(names);
    }

    /**
     * 查询列下标。
     *
     * @param name 列名。
     * @return 下标；列不存在返回 {@code -1} 。
     */
    public int indexOf(String name) {
        Integer index = this.positions.get(name);
        return (null == index) ? -1 : index;
    }

    /**
     * 查询指定下标的列名。
     *
     * @param index 下标。
     * @return 列名；越界返回 {@code null} 。
     */
    public String nameOf(int index) {
        if (index < 0 || index >= this.names.length) {
            return null;
        }

        return this.names[index];
    }

    /**
     * 列数。
     *
     * @return 列数。
     */
    public int size() {
        return this.names.length;
    }
}
