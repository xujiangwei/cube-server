/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.storage;

import cell.core.talk.LiteralBase;
import cell.util.log.Logger;
import cube.core.StorageField;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 流式读取用的行值提取与资源释放工具。
 *
 * <p>核心是<b>保留 SQL NULL</b>：{@code getLong} 对 NULL 返回 0，直接装箱会把
 * "空值"变成"零值"。新行 API 统一用 {@code wasNull()} 区分二者；
 * 旧的带类型 {@code executeQuery} 仍保持原有的数值 getter 行为。</p>
 */
final class StorageRows {

    private StorageRows() {
    }

    /** 快照投影，空数组与 SQLUtils 的 SELECT * 语义一致。 */
    static StorageField[] copyFields(StorageField[] fields) {
        if (null == fields || 0 == fields.length) {
            return null;
        }
        StorageField[] copy = fields.clone();
        for (StorageField field : copy) {
            if (null == field) {
                throw new IllegalArgumentException("Query fields must not contain null");
            }
        }
        return copy;
    }

    /**
     * 按声明类型读取一列，SQL NULL 返回 {@code null} 。
     *
     * @param resultSet 结果集。
     * @param index 列下标，从 1 开始。
     * @param literalBase 声明类型，允许为 {@code null}（此时退化为 {@code getObject}）。
     * @return 列值，可能为 {@code null}。
     * @throws SQLException 读取失败时抛出。
     */
    static Object read(ResultSet resultSet, int index, LiteralBase literalBase) throws SQLException {
        if (null == literalBase) {
            return resultSet.getObject(index);
        }

        switch (literalBase) {
            case LONG: {
                long value = resultSet.getLong(index);
                return resultSet.wasNull() ? null : Long.valueOf(value);
            }
            case INT: {
                int value = resultSet.getInt(index);
                return resultSet.wasNull() ? null : Integer.valueOf(value);
            }
            case BOOL: {
                boolean value = resultSet.getBoolean(index);
                return resultSet.wasNull() ? null : Boolean.valueOf(value);
            }
            case STRING:
                return resultSet.getString(index);
            default:
                return resultSet.getObject(index);
        }
    }

    static boolean closeQuietly(ResultSet resultSet) {
        if (null == resultSet) {
            return true;
        }

        try {
            resultSet.close();
            return true;
        } catch (SQLException e) {
            Logger.d(StorageRows.class, "#closeQuietly - Failed to close result set: " + e.getMessage());
            return false;
        }
    }

    static boolean closeQuietly(Statement statement) {
        if (null == statement) {
            return true;
        }

        try {
            statement.close();
            return true;
        } catch (SQLException e) {
            Logger.d(StorageRows.class, "#closeQuietly - Failed to close statement: " + e.getMessage());
            return false;
        }
    }
}
