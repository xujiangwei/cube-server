/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.storage;

import cube.core.Storage;
import cell.util.log.Logger;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储工厂。
 */
public final class StorageFactory {

    /** SQLite 配置项：数据库文件路径。 */
    public final static String SQLITE_FILE = SQLiteStorage.CONFIG_FILE;

    /** MySQL 配置项：主机地址。 */
    public final static String MYSQL_HOST = MySQLStorage.CONFIG_HOST;
    /** MySQL 配置项：端口。 */
    public final static String MYSQL_PORT = MySQLStorage.CONFIG_PORT;
    /** MySQL 配置项：库名。 */
    public final static String MYSQL_SCHEMA = MySQLStorage.CONFIG_SCHEMA;
    /** MySQL 配置项：用户名。 */
    public final static String MYSQL_USER = MySQLStorage.CONFIG_USER;
    /** MySQL 配置项：密码。 */
    public final static String MYSQL_PASSWORD = MySQLStorage.CONFIG_PASSWORD;

    private final static StorageFactory instance = new StorageFactory();

    private ConcurrentHashMap<String, Storage> storageMap;

    private StorageFactory() {
        this.storageMap = new ConcurrentHashMap<>();
    }

    /**
     * 获取工厂单例。
     *
     * @return 全局唯一的存储工厂。
     */
    public static StorageFactory getInstance() {
        return StorageFactory.instance;
    }

    /**
     * 创构建存储。
     *
     * @param type 存储类型。
     * @param name 存储名称。
     * @param config 存储的配置。
     * @return 返回存储实例。
     */
    public Storage createStorage(StorageType type, String name, JSONObject config) {
        Storage storage = null;

        if (type == StorageType.SQLite) {
            storage = new SQLiteStorage(name);
        }
        else if (type == StorageType.MySQL) {
            storage = new MySQLStorage(name);
        }

        if (null != storage) {
            storage.configure(config);
            this.storageMap.put(name, storage);
        }

        return storage;
    }

    /**
     * 获取指定名称的存储。
     *
     * @param name 指定存储器名称。
     * @return 返回存储器实例。
     */
    public Storage getStorage(String name) {
        return this.storageMap.get(name);
    }

    /**
     * 获取指定存储的类型。
     *
     * @param storage 指定存储器。
     * @return 返回存储器类型。
     */
    public StorageType getStorageType(Storage storage) {
        if (storage instanceof  MySQLStorage) {
            return StorageType.MySQL;
        }
        else if (storage instanceof SQLiteStorage) {
            return StorageType.SQLite;
        }
        else {
            return StorageType.Other;
        }
    }

    /**
     * 检查存储是否就绪，未就绪时记录一条带归属的 ERROR。
     *
     * <p>{@link Storage#open()} 不返回结果，驱动缺失或配置错误时只能靠日志发现。
     * 各存储包装类在 {@code open()} 之后调用本方法，把"谁没打开"写清楚。</p>
     *
     * @param storage 存储实例。
     * @param owner 调用方名称，用于定位。
     * @return 就绪返回 {@code true} 。
     */
    public static boolean ensureOpen(Storage storage, String owner) {
        if (null == storage) {
            Logger.e(StorageFactory.class, "#ensureOpen - Storage is null, owner: " + owner);
            return false;
        }

        if (!storage.isOpen()) {
            Logger.e(StorageFactory.class, "#ensureOpen - Storage is not ready, owner: " + owner
                    + ", name: " + storage.getName());
            return false;
        }

        return true;
    }

    /**
     * 关闭并移除所有已创建的存储。
     *
     * <p>存储由各业务模块自行 {@code open()}，但关闭一直没人负责：进程退出时连接靠操作系统断开，
     * 优雅停机可能丢掉未提交的事务。由 {@code Kernel#shutdown()} 统一调用。</p>
     */
    public void closeAll() {
        for (String name : this.storageMap.keySet()) {
            Storage storage = this.storageMap.remove(name);
            if (null == storage) {
                continue;
            }

            try {
                storage.close();
            } catch (Exception e) {
                Logger.e(StorageFactory.class, "#closeAll - Failed to close storage: " + name, e);
            }
        }
    }
}
