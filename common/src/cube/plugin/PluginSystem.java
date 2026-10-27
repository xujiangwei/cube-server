/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.plugin;

import cell.util.log.Logger;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件系统。
 *
 * <p>泛型参数为钩子类型。</p>
 */
public class PluginSystem<T extends Hook> {

    /**
     * 是否使用 Lua 支持。
     */
    public static boolean useLua = false;

    /**
     * 钩子列表。
     */
    private ConcurrentHashMap<String, Hook> hooks;

    /**
     * 插件列表。
     */
    private ConcurrentHashMap<String, List<Plugin>> plugins;

    public PluginSystem() {
        this.hooks = new ConcurrentHashMap<>();
        this.plugins = new ConcurrentHashMap<>();
    }

    /**
     * 加载插件系统。
     */
    public static void load() {
        Logger.i("PluginSystem", "Start plugin system");

        if (PluginSystem.useLua) {
            try {
                System.loadLibrary("luajava-1.1");
            } catch (UnsatisfiedLinkError e) {
                System.err.println("Native code library failed to load.\n" + e);
                Logger.e("PluginSystem", "Native code library failed to load.", e);
            }
        }
    }

    /**
     * 卸载插件系统。
     */
    public static void unlaod() {
        Logger.i("PluginSystem", "Stop plugin system");
    }

    /**
     * 添加钩子。
     *
     * @param hook 钩子实例。
     */
    public void addHook(T hook) {
        this.hooks.put(hook.getKey(), hook);
        hook.system = this;
    }

    /**
     * 移除钩子。
     *
     * @param hook 钩子实例。
     */
    public void removeHook(T hook) {
        this.hooks.remove(hook.getKey());
        hook.system = null;
    }

    /**
     * 返回指定钩子。
     *
     * @param key 钩子键。
     * @return 钩子；未注册返回 {@code null} 。
     */
    public T getHook(String key) {
        return (T) this.hooks.get(key);
    }

    /**
     * 注册指定键的插件。
     *
     * @param key    钩子键。
     * @param plugin 插件实例。
     */
    public void register(String key, Plugin plugin) {
        List<Plugin> list = this.plugins.get(key);
        if (null == list) {
            list = new Vector<>();
            this.plugins.put(key, list);
        }

        if (list.contains(plugin)) {
            return;
        }

        (new Thread() {
            @Override
            public void run() {
                // 调用 setup 进行准备
                plugin.setup();
            }
        }).start();

        list.add(plugin);
    }

    /**
     * 按类名注册插件。
     *
     * @param key       钩子键。
     * @param className 插件实现类名。
     */
    public void register(String key, String className) {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        try {
            Class<?> clazz = loader.loadClass(className);
            Plugin plugin = (Plugin) clazz.getDeclaredConstructor().newInstance();

            Logger.i(this.getClass(), "New & register plugin : " + className);

            this.register(key, plugin);
        } catch (ClassNotFoundException e) {
            Logger.e(PluginSystem.class, "#register - Class not found", e);
        } catch (IllegalAccessException e) {
            Logger.e(PluginSystem.class, "#register - Illegal access", e);
        } catch (InstantiationException e) {
            Logger.e(PluginSystem.class, "#register - Instantiation error", e);
        } catch (NoSuchMethodException e) {
            Logger.e(PluginSystem.class, "#register - Method not found", e);
        } catch (InvocationTargetException e) {
            Logger.e(PluginSystem.class, "#register - Invocation target error", e);
        }
    }

    /**
     * 注销指定插件。
     *
     * @param key    钩子键。
     * @param plugin 插件实例。
     */
    public void deregister(String key, Plugin plugin) {
        List<Plugin> list = this.plugins.get(key);
        if (null == list) {
            return;
        }

        if (list.remove(plugin)) {
            (new Thread() {
                @Override
                public void run() {
                    plugin.teardown();
                }
            }).start();
        }
    }

    /**
     * 依次执行同一钩子下的全部插件。
     *
     * <p>任一插件返回 {@code NOT_ALLOWED} 时立即中断并返回该结果。</p>
     *
     * @param key     钩子键。
     * @param context 插件上下文。
     * @return 合并后的执行结果。
     */
    protected HookResult apply(String key, PluginContext context) {
        HookResult result = new HookResult();

        List<Plugin> list = this.plugins.get(key);
        if (null == list) {
            return result;
        }

        for (Plugin plugin : list) {
            HookResult hr = plugin.launch(context);
            if (null != hr) {
                result.add(hr);
            }
        }
        return result;
    }

    /**
     * 获取已加载的插件。
     *
     * @return 插件列表；无插件时为空列表。
     */
    public List<Plugin> getPlugins() {
        List<Plugin> list = new ArrayList<>();
        for (List<Plugin> plugins : this.plugins.values()) {
            list.addAll(plugins);
        }
        return list;
    }


}
