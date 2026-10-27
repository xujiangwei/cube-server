/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher;

import cell.api.Nucleus;
import cell.api.Servable;
import cell.carpet.CellListener;
import cell.util.log.LogManager;
import cell.util.log.Logger;
import cube.report.ReportService;
import cube.util.ConfigUtils;
import cube.util.HttpConfig;
import cube.util.NodeName;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Timer;

/**
 * 网关容器的监听器。
 */
public class DispatcherListener implements CellListener {

    private Timer timer;

    private Daemon daemon;

    private List<String> cellets;

    public DispatcherListener() {
        Logger.i(this.getClass(), "--------------------------------");
        Logger.i(this.getClass(), "Version " + Version.toVersionString());
        Logger.i(this.getClass(), "--------------------------------");
    }

    /**
     * 容器预初始化。
     *
     * <p>创建执行机并放入容器参数，随后创建守护任务、注册日志句柄并加载配置。
     * 此时网络服务尚未启动，因此只做本地准备工作。</p>
     *
     * @param nucleus 容器内核。
     */
    @Override
    public void cellPreinitialize(Nucleus nucleus) {
        Performer performer = new Performer(nucleus);
        nucleus.setParameter("performer", performer);

        this.daemon = new Daemon(performer);
        LogManager.getInstance().addHandle(this.daemon);

        // 从配置文件加载配置数据
        this.config(performer);
    }

    /**
     * 容器初始化完成。
     *
     * <p>启动执行机并清空待装载单元列表，随后启动守护定时器（首次延迟 30 秒、周期 10 秒），
     * 最后初始化管理端点。</p>
     *
     * @param nucleus 容器内核。
     */
    @Override
    public void cellInitialized(Nucleus nucleus) {
        Performer performer = (Performer) nucleus.getParameter("performer");
        performer.start(this.cellets);
        this.cellets.clear();
        this.cellets = null;

        this.timer = new Timer();
        this.timer.schedule(this.daemon, 30 * 1000, 10 * 1000);

        // 配置管理信息
        this.initManagement(performer, nucleus);
    }

    /**
     * 容器销毁前回调。
     *
     * <p>当前无事可做：资源统一在 {@link #cellDestroyed(Nucleus)} 中释放。</p>
     *
     * @param nucleus 容器内核。
     */
    @Override
    public void cellPredestroy(Nucleus nucleus) {
        // Nothing
    }

    /**
     * 容器已销毁。
     *
     * <p>先取消守护定时器再停止执行机，顺序不能颠倒——
     * 否则定时器可能在执行机停止后仍触发上报。</p>
     *
     * @param nucleus 容器内核。
     */
    @Override
    public void cellDestroyed(Nucleus nucleus) {
        if (null != this.timer) {
            this.timer.cancel();
            this.timer = null;
        }

        Performer performer = (Performer) nucleus.getParameter("performer");
        performer.stop();
    }

    private void config(Performer performer) {
        File file = new File("config/dispatcher.properties");
        if (!file.exists()) {
            file = new File("dispatcher.properties");
            if (!file.exists()) {
                Logger.w(this.getClass(), "Can NOT find config file");
                return;
            }
        }

        Properties properties = new Properties();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            properties.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != fis) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }

        // 读取管理的 Cellet 名称
        String celletsConfig = properties.getProperty("cellets");
        String[] celletNames = celletsConfig.split(",");
        this.cellets = new ArrayList<>(celletNames.length);
        // 默认都允许访问 Client
        this.cellets.add("Client");
        for (String cellet : celletNames) {
            this.cellets.add(cellet.trim());
        }

        // 读取路由配置
        String prefix = "director.";
        for (int i = 1; i <= 10; ++i) {
            String keyAddress = prefix + (i) + ".address";
            if (!properties.containsKey(keyAddress)) {
                continue;
            }

            String keyPort = prefix + (i) + ".port";
            String keyFileAddress = prefix + (i) + ".fs.address";
            String keyFilePort = prefix + (i) + ".fs.port";
            String keyCellets = prefix + (i) + ".cellets";

            String address = properties.getProperty(keyAddress);
            int port = Integer.parseInt(properties.getProperty(keyPort, "6000"));

            String fsAddress = properties.getProperty(keyFileAddress, address);
            int fsPort = Integer.parseInt(properties.getProperty(keyFilePort, "6080"));

            String celletString = properties.getProperty(keyCellets);
            String[] cellets = celletString.split(",");

            // 设定范围
            Scope scope = new Scope();
            for (String cellet : cellets) {
                scope.cellets.add(cellet.trim());
            }
            // 添加导演机节点
            performer.addDirector(address, port, fsAddress, fsPort, scope);

            Logger.i(this.getClass(), "Add director point " + address + ":" + port);
        }

        // 加载 HTTP 配置
        HttpConfig httpConfig = this.loadHttpConfig(properties);
        performer.configHttpServer(httpConfig);

        // 设置属性
        performer.setProperties(properties);
    }

    private HttpConfig loadHttpConfig(Properties properties) {
        HttpConfig config = new HttpConfig();

        config.httpPort = Integer.parseInt(properties.getProperty("http.port", "7010"));
        config.httpsPort = Integer.parseInt(properties.getProperty("https.port", "0"));
        config.keystore = properties.getProperty("keystore", "");
        config.storePassword = properties.getProperty("storePassword", "");
        config.managerPassword = properties.getProperty("managerPassword", "");
        config.maxThreads = Integer.parseInt(properties.getProperty("maxThreads", "32"));
        config.minThreads = Integer.parseInt(properties.getProperty("minThreads", "4"));

        return config;
    }

    private void initManagement(Performer performer, Nucleus nucleus) {
        // 配置控制台
        try {
            Properties properties = ConfigUtils.readProperties("config/console-follower-dispatcher.properties");

            // 设置接收报告的服务器
            ReportService.getInstance().addHost(properties.getProperty("console.host"),
                    Integer.parseInt(properties.getProperty("console.port", "7080")));

            // 设置节点名
            String identity = NodeName.identity(properties.getProperty(NodeName.KEY_NODE_ID));
            int port = 0;
            for (Servable server : nucleus.getTalkService().getServers()) {
                if (server.getClass().getName().equals("cell.core.talk.Server")) {
                    port = server.getPort();
                    break;
                }
            }
            String defaultName = NodeName.makeName(identity, NodeName.ROLE_DISPATCHER, port);
            performer.setNodeName(properties.getProperty("name", defaultName));
            Logger.i(this.getClass(), "Node name: " + performer.getNodeName()
                    + " (identity: " + identity + ")");
        } catch (IOException e) {
            Logger.e(this.getClass(), "Read console follower config failed", e);
        }
    }
}
