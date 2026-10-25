/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

import cube.storage.StorageType;
import org.json.JSONObject;

import java.util.List;

/**
 * 存储器接口。
 */
public interface Storage {

    /**
     * 获取存储器名称。
     *
     * @return
     */
    String getName();

    /**
     * 获取配置。
     *
     * @return
     */
    JSONObject getConfig();

    /**
     * 获取类型。
     *
     * @return
     */
    StorageType getType();

    /**
     *
     * @param config
     */
    void configure(JSONObject config);

    /**
     * 打开存储仓库。
     *
     * <p>本方法不返回结果：实现内部失败时只记录日志。需要判断是否可用时调用 {@link #isOpen()}。</p>
     */
    void open();

    /**
     * 关闭存储仓库。
     */
    void close();

    /**
     * 存储是否已就绪。
     *
     * <p>默认返回 {@code true}，由具体实现按自身状态覆盖。约定：{@code open()} 之后才能返回
     * {@code true}；{@code close()} 之后必须返回 {@code false}。</p>
     *
     * @return 就绪返回 {@code true} 。
     */
    default boolean isOpen() {
        return true;
    }

    boolean exist(String table);

    boolean executeCreate(String table, StorageField[] fields);

    boolean executeInsert(String table, StorageField[] fields);

    boolean executeInsert(String table, List<StorageField[]> fieldsList);

    boolean executeUpdate(String table, StorageField[] fields, Conditional[] conditionals);

    boolean executeDelete(String table, Conditional[] conditionals);

    /**
     * 查询数据。
     *
     * <p><b>契约</b>：查询失败返回 {@code null} 并记录日志；查询成功但无匹配数据返回空列表。
     * 调用方必须判空——两者语义不同，不能都当作"没有数据"。</p>
     *
     * @param table 表名。
     * @param fields 字段列表，{@code null} 表示全部字段。
     * @return 查询结果；失败返回 {@code null} 。
     */
    List<StorageField[]> executeQuery(String table, StorageField[] fields);

    /**
     * 查询数据。
     *
     * <p><b>契约</b>：同 {@link #executeQuery(String, StorageField[])}——失败返回 {@code null}，
     * 无数据返回空列表。需要区分失败与空结果时使用
     * {@link #executeQueryOrThrow(String, StorageField[], Conditional[])}。</p>
     *
     * @param table 表名。
     * @param fields 字段列表，{@code null} 表示全部字段。
     * @param conditionals 条件列表，允许为 {@code null} 。
     * @return 查询结果；失败返回 {@code null} 。
     */
    List<StorageField[]> executeQuery(String table, StorageField[] fields, Conditional[] conditionals);

    List<StorageField[]> executeQuery(String[] tables, StorageField[] fields, Conditional[] conditionals);

    List<StorageField[]> executeQuery(String sql);

    /**
     * 查询数据，失败时抛出异常。
     *
     * <p>与 {@link #executeQuery(String, StorageField[], Conditional[])} 的唯一区别是失败语义：
     * 前者返回 {@code null}，本方法抛 {@link StorageException}。新代码优先使用本方法，
     * 避免"忘记判空"导致 NPE。</p>
     *
     * @param table 表名。
     * @param fields 字段列表，{@code null} 表示全部字段。
     * @param conditionals 条件列表，允许为 {@code null} 。
     * @return 查询结果，永不为 {@code null} （无数据时为空列表）。
     * @throws StorageException 查询失败时抛出。
     */
    default List<StorageField[]> executeQueryOrThrow(String table, StorageField[] fields,
                                                     Conditional[] conditionals) {
        List<StorageField[]> rows = this.executeQuery(table, fields, conditionals);
        if (null == rows) {
            throw new StorageException("Query failed: " + table);
        }

        return rows;
    }

    boolean execute(String sql);
}
