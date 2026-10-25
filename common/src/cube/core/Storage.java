/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

import cube.storage.StorageType;
import org.json.JSONObject;

import java.util.ArrayList;
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

    /**
     * 逐行遍历查询结果，不把结果集物化进内存。
     *
     * <p><b>默认实现不支持</b>：只有真正实现了流式读取的存储器才覆盖本方法。
     * 这样新增接口方法不会破坏外部实现类的二进制兼容。</p>
     *
     * <p><b>契约</b>：</p>
     * <ol>
     *     <li>{@code values} 数组在回调之间复用，回调返回后内容会被下一行覆盖，
     *     需要保留数据必须自行 {@code clone()}；</li>
     *     <li>遍历期间当前连接被本次查询独占，回调里禁止再对同一个存储发起查询；</li>
     *     <li>查询失败抛 {@link StorageException}（与 {@link #executeQueryOrThrow} 一致）。</li>
     * </ol>
     *
     * @param table 表名。
     * @param fields 字段列表，{@code null} 表示全部字段。
     * @param conditionals 条件列表，允许为 {@code null} 。
     * @param visitor 行访问器，返回 {@code false} 终止遍历。
     * @throws StorageException 查询失败或当前实现不支持时抛出。
     */
    default void forEachRow(String table, StorageField[] fields, Conditional[] conditionals,
                            RowVisitor visitor) {
        throw new UnsupportedOperationException("forEachRow is not supported by "
                + this.getClass().getName());
    }

    /**
     * 查询并逐行映射成业务对象。
     *
     * <p>与 {@link #executeQuery} 的差别：不产生 {@link StorageField} 中间层，
     * 每行只留下调用方自己的对象，适合列表、批量读取等热点路径。</p>
     *
     * <p>默认实现在 {@link #forEachRow} 之上实现；存储器不支持流式读取时抛
     * {@link UnsupportedOperationException}。</p>
     *
     * @param table 表名。
     * @param fields 字段列表，{@code null} 表示全部字段。
     * @param conditionals 条件列表，允许为 {@code null} 。
     * @param mapper 行映射器。
     * @param <T> 映射结果类型。
     * @return 结果列表，永不为 {@code null} 。
     * @throws StorageException 查询失败时抛出。
     */
    default <T> List<T> queryList(String table, StorageField[] fields, Conditional[] conditionals,
                                  final RowMapper<T> mapper) {
        final List<T> result = new ArrayList<>();
        this.forEachRow(table, fields, conditionals, new RowVisitor() {
            @Override
            public boolean visit(Object[] values, ColumnIndex columns) {
                result.add(mapper.map(values, columns));
                return true;
            }
        });

        return result;
    }

    boolean execute(String sql);
}
