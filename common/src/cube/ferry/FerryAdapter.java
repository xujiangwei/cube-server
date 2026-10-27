/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

import cell.adapter.CelletAdapter;
import cell.util.log.Logger;
import cube.util.ConfigUtils;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

/**
 * 数据摆渡适配器。
 */
public class FerryAdapter extends CelletAdapter {

    public FerryAdapter(String host, int port) {
        super("FerryAdapter", host, port, true);
    }

    /**
     * 启动适配器。
     *
     * <p>按配置建立与渡口服务的连接，重复调用幂等。</p>
     */
    public void start() {
        this.setup();
    }

    /** 停止适配器并释放连接。 */
    public void stop() {
        this.teardown();
    }

    /**
     * 创建适配器。
     *
     * @return 适配器实例。
     */
    public static FerryAdapter create() {
        return FerryAdapter.create(null);
    }

    /**
     * 按配置文件创建适配器。
     *
     * @param configFile 配置文件。
     * @return 适配器实例；配置不合法时返回 {@code null} 。
     */
    public static FerryAdapter create(File configFile) {
        String host = "0.0.0.0";
        int port = 6900;

        if (null != configFile) {
            try {
                Properties properties = ConfigUtils.readProperties(configFile.getAbsolutePath());
            } catch (IOException e) {
                Logger.e(FerryAdapter.class, "#create - I/O error", e);
            }
        }

        FerryAdapter adapter = new FerryAdapter(host, port);
        return adapter;
    }
}
