/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

import cell.util.log.Logger;
import cube.plugin.Plugin;
import cube.plugin.PluginSystem;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件管理器。
 */
public class PluginManager {

    private Kernel kernel;

    private JSONObject config;

    private volatile boolean started = false;

    /**
     * 已部署的插件描述。
     *
     * <p>必须持有到 {@link #stop()}：每个描述拥有一个 {@link URLClassLoader}，
     * 不关闭会导致 jar 句柄泄漏（Linux 下无法替换 jar，Windows 下文件被占用）。</p>
     */
    private final List<PluginDesc> deployed = new ArrayList<>();

    public PluginManager(Kernel kernel) {
        this.kernel = kernel;
    }

    /**
     * 启动插件管理器。
     *
     * <p>扫描插件目录、加载并初始化插件，重复调用幂等。</p>
     */
    public void start() {
        this.config = this.readConfig();
        if (null != this.config) {
            List<PluginDesc> list = new ArrayList<>();

            if (this.config.has("deploy")) {
                try {
                    JSONArray array = this.config.getJSONArray("deploy");
                    for (int i = 0, len = array.length(); i < len; ++i) {
                        JSONObject plugin = array.getJSONObject(i);
                        PluginDesc desc = new PluginDesc(plugin);
                        if (desc.isValid()) {
                            list.add(desc);
                        } else {
                            // 无效描述可能已创建 ClassLoader，必须显式关闭
                            desc.close();
                        }
                    }
                } catch (JSONException e) {
                    Logger.e(PluginManager.class, "#start - JSON error", e);
                }
            }

            // 注册插件
            for (PluginDesc desc : list) {
                Module module = this.kernel.getModule(desc.module);
                if (null == module) {
                    // 模块缺失不应阻断宿主启动：跳过并记录
                    Logger.w(this.getClass(), "#start - No such module: " + desc.module
                            + ", skip plugin deployment");
                    desc.close();
                    continue;
                }

                PluginSystem ps = module.getPluginSystem();
                if (null == ps) {
                    Logger.w(this.getClass(), "#start - No plugin system for module: " + desc.module);
                    desc.close();
                    continue;
                }

                for (Map.Entry<String, Plugin> e : desc.pluginMap.entrySet()) {
                    ps.register(e.getKey(), e.getValue());
                    Logger.i(this.getClass(), "Register plugin : #" + desc.module + " [" + e.getKey() + "] - " + e.getValue().getClass().getName());
                }

                this.deployed.add(desc);
            }

            list.clear();

            this.started = true;
        }
    }

    /**
     * 停止插件管理器并卸载全部插件。
     */
    public void stop() {
        if (!this.started) {
            return;
        }

        this.started = false;

        for (AbstractModule module : this.kernel.getModules()) {
            PluginSystem pluginSystem = module.getPluginSystem();
            if (null == pluginSystem) {
                // 跳过没有插件系统的模块
                continue;
            }

            List<Plugin> plugins = pluginSystem.getPlugins();
            for (Plugin plugin : plugins) {
                plugin.teardown();
            }
        }

        // 先 teardown 再关闭 ClassLoader
        for (PluginDesc desc : this.deployed) {
            desc.close();
        }
        this.deployed.clear();
    }

    private JSONObject readConfig() {
        File file = new File("config/plugin.json");
        if (!file.exists()) {
            file = new File("plugin.json");
            if (!file.exists()) {
                return null;
            }
        }

        JSONObject json = null;

        StringBuilder buf = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line = null;
            while ((line = reader.readLine()) != null) {
                buf.append(line);
            }
        } catch (Exception e) {
            Logger.e(PluginManager.class, "#readConfig - Unhandled exception", e);
        } finally {
            if (null != reader) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Logger.e(PluginManager.class, "#readConfig - I/O error", e);
                }
            }
        }

        try {
            json = new JSONObject(buf.toString());
        } catch (JSONException e) {
            Logger.e(PluginManager.class, "#readConfig - JSON error", e);
        }

        return json;
    }

    /**
     * 插件信息描述。
     */
    protected class PluginDesc {

        protected String module = null;

        protected Map<String, Plugin> pluginMap;

        /**
         * 插件 jar 的类加载器，生命周期由 {@link PluginManager} 管理。
         */
        protected URLClassLoader loader;

        protected PluginDesc(JSONObject json) {
            try {
                String filepath = json.getString("file");
                File file = new File(filepath);
                if (file.exists()) {
                    // 读取数据
                    this.module = json.getString("module");
                    this.pluginMap = new HashMap<>();

                    this.loader = new URLClassLoader(new URL[]{file.toURI().toURL()});

                    // 读取插件列表
                    JSONArray list = json.getJSONArray("plugins");
                    for (int i = 0, len = list.length(); i < len; ++i) {
                        JSONObject pluginJson = list.getJSONObject(i);
                        String hook = pluginJson.getString("hook");
                        String className = pluginJson.getString("class");

                        try {
                            Class clazz = this.loader.loadClass(className);
                            Plugin plugin = (Plugin) clazz.getDeclaredConstructor().newInstance();
                            this.pluginMap.put(hook, plugin);
                        } catch (ClassNotFoundException e) {
                            Logger.w(this.getClass(), "#PluginDesc", e);
                            continue;
                        } catch (IllegalAccessException e) {
                            Logger.w(this.getClass(), "#PluginDesc", e);
                            continue;
                        } catch (InstantiationException e) {
                            Logger.w(this.getClass(), "#PluginDesc", e);
                            continue;
                        } catch (Exception e) {
                            Logger.w(this.getClass(), "#PluginDesc", e);
                            continue;
                        }
                    }
                }
            } catch (JSONException e) {
                Logger.e(PluginManager.class, "#readConfig - JSON error", e);
            } catch (MalformedURLException e) {
                Logger.e(PluginManager.class, "#readConfig - Malformed URL", e);
            }
        }

        protected boolean isValid() {
            return (null != this.module) && (null != this.loader);
        }

        /**
         * 关闭插件类加载器，释放 jar 句柄。
         */
        protected void close() {
            if (null != this.loader) {
                try {
                    this.loader.close();
                } catch (IOException e) {
                    Logger.d(PluginManager.class, "#close - Failed to close class loader: " + e.getMessage());
                }
                this.loader = null;
            }
        }
    }
}
