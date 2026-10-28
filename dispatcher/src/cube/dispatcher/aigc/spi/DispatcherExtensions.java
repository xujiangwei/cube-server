/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.dispatcher.aigc.spi;

import cell.util.log.Logger;
import cube.util.ConfigUtils;
import org.eclipse.jetty.server.handler.ContextHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * 业务模块扩展发现器。
 *
 * <p><b>为何用显式配置而非 {@code ServiceLoader}</b>：本工程是 ant + 手写启动脚本，
 * classpath 顺序不确定；且模块启停需要运维开关（心理学是付费域，巡检是实验室域），
 * 配置文件天然承载。风格与 {@code config/aigc-modules.properties} 一致。</p>
 *
 * <p>配置项：一行一个扩展，键为 {@code module.extensions.<n>}，值为实现类全限定名，
 * 按 {@code <n>} 升序装载。旧的逗号分隔写法 {@code module.extensions=A,B,C} 仅作兼容保留。
 * 未配置或为空时返回空列表，此时不注册任何扩展。</p>
 *
 * <p><b>本侧只持有接口</b>：实现类由各业务模块提供（编译期反向依赖本模块），
 * 本类仅按类名反射装载，运行期不引用任何实现包中的类型。</p>
 *
 * <p><b>前缀冲突策略</b>：两个扩展声明同一前缀时，<b>先注册者保留</b>，
 * 后者记 ERROR 并跳过，绝不覆盖——否则一个模块的接入会静默改变另一模块的端点归属。
 *
 * <p><b>端点路径冲突策略</b>：同上，按 {@link ContextHandler#getContextPath()}
 * 逐条比对，先注册者保留。Jetty 的 {@code ContextHandlerCollection}
 * 对同一路径是<b>后者覆盖前者</b>，不做检查会让后一个模块静默夺走前者的端点。</p>
 */
public final class DispatcherExtensions {

    /**
     * 配置项名称。旧写法：逗号分隔的类名列表（已废弃，仅作兼容）。
     */
    public final static String CONFIG_EXTENSIONS = "module.extensions";

    /**
     * 配置项前缀。新写法：{@code module.extensions.<n>=<类全限定名>}，一行一个。
     */
    public final static String CONFIG_EXTENSIONS_PREFIX = CONFIG_EXTENSIONS + ".";

    private DispatcherExtensions() {
    }

    /**
     * 发现并实例化全部扩展。
     *
     * @return 返回扩展列表；未配置或全部实例化失败时返回空列表。
     */
    public static List<DispatcherExtension> load() {
        List<DispatcherExtension> extensions = new ArrayList<>();

        File file = new File("config/dispatcher.properties");
        if (!file.exists()) {
            file = new File("dispatcher.properties");
        }

        if (!file.exists()) {
            Logger.i(DispatcherExtensions.class, "#load - Config file NOT found, no extension is loaded");
            return extensions;
        }

        Properties properties;
        try {
            properties = ConfigUtils.readProperties(file.getAbsolutePath());
        } catch (Exception e) {
            Logger.e(DispatcherExtensions.class, "#load - Read config failed: " + file.getAbsolutePath(), e);
            return extensions;
        }

        if (null == properties) {
            Logger.e(DispatcherExtensions.class, "#load - Config is NULL: " + file.getAbsolutePath());
            return extensions;
        }

        List<String> classNames = resolveClassNames(properties);
        if (classNames.isEmpty()) {
            Logger.i(DispatcherExtensions.class, "#load - \"" + CONFIG_EXTENSIONS_PREFIX
                    + "<n>\" is empty, no extension is loaded");
            return extensions;
        }

        for (String name : classNames) {
            DispatcherExtension extension = instantiate(name);
            if (null != extension) {
                extensions.add(extension);
            }
        }

        Logger.i(DispatcherExtensions.class, "#load - Extensions loaded: "
                + extensions.size() + "/" + classNames.size());

        return extensions;
    }

    /**
     * 解析配置中的扩展类名列表，返回结果即装载顺序。
     *
     * <p><b>推荐写法（一行一个）</b>：{@code module.extensions.<n>=<类全限定名>}，
     * 按 {@code <n>} 升序装载。{@code <n>} 只需唯一，<b>允许跳号</b>
     * （建议按 10/20/30 编排，中间插入新扩展时无需重排后续编号，
     * 增删一个扩展在配置文件里只产生一行 diff）。</p>
     *
     * <p><b>旧写法</b>：{@code module.extensions=A,B,C}，仅为兼容已上线的部署配置保留。
     * 只要存在任一新写法条目，旧写法整体失效，两者不会混用。</p>
     *
     * @param properties 配置文件内容。
     * @return 返回按装载顺序排列的类名；未配置时返回空列表。
     */
    static List<String> resolveClassNames(Properties properties) {
        List<String> classNames = new ArrayList<>();
        if (null == properties) {
            return classNames;
        }

        // TreeMap：按 <n> 升序装载，与配置文件里的书写顺序无关
        Map<Integer, String> indexed = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(CONFIG_EXTENSIONS_PREFIX)) {
                continue;
            }

            String suffix = key.substring(CONFIG_EXTENSIONS_PREFIX.length()).trim();
            int index;
            try {
                index = Integer.parseInt(suffix);
            } catch (NumberFormatException e) {
                Logger.w(DispatcherExtensions.class, "#resolveClassNames - Invalid key \"" + key
                        + "\", expected \"" + CONFIG_EXTENSIONS_PREFIX + "<n>\", IGNORED");
                continue;
            }

            String name = properties.getProperty(key, "").trim();
            if (name.isEmpty()) {
                Logger.w(DispatcherExtensions.class, "#resolveClassNames - Empty value of \"" + key
                        + "\", IGNORED");
                continue;
            }

            if (indexed.containsKey(index)) {
                Logger.e(DispatcherExtensions.class, "#resolveClassNames - Duplicate index " + index
                        + ", IGNORED \"" + name + "\"");
                continue;
            }

            indexed.put(index, name);
        }

        String legacy = properties.getProperty(CONFIG_EXTENSIONS, "").trim();

        if (!indexed.isEmpty()) {
            if (!legacy.isEmpty()) {
                Logger.w(DispatcherExtensions.class, "#resolveClassNames - Both \"" + CONFIG_EXTENSIONS
                        + "\" and \"" + CONFIG_EXTENSIONS_PREFIX + "<n>\" are present,"
                        + " the indexed entries WIN and the comma separated list is IGNORED");
            }

            for (Map.Entry<Integer, String> entry : indexed.entrySet()) {
                addClassName(classNames, entry.getValue());
            }

            return classNames;
        }

        if (legacy.isEmpty()) {
            return classNames;
        }

        Logger.w(DispatcherExtensions.class, "#resolveClassNames - \"" + CONFIG_EXTENSIONS
                + "\" (comma separated) is DEPRECATED, use \"" + CONFIG_EXTENSIONS_PREFIX
                + "<n>\" with one extension per line");

        for (String part : legacy.split(",")) {
            addClassName(classNames, part);
        }

        return classNames;
    }

    /**
     * 追加一个类名，跳过空白项和重复项。
     *
     * <p>重复声明会导致同一个扩展被实例化两次，其前缀与端点必然被判为冲突，
     * 徒增 ERROR 日志且掩盖真实配置错误，故此处直接去重。</p>
     *
     * @param classNames 目标列表。
     * @param name 类全限定名。
     */
    private static void addClassName(List<String> classNames, String name) {
        if (null == name) {
            return;
        }

        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        if (classNames.contains(trimmed)) {
            Logger.w(DispatcherExtensions.class, "#addClassName - \"" + trimmed
                    + "\" is declared more than once, only the first occurrence is loaded");
            return;
        }

        classNames.add(trimmed);
    }

    /**
     * 收集全部扩展声明的前缀，并检测冲突。
     *
     * @param extensions 扩展列表。
     * @return 返回去重后的前缀集合。
     */
    public static Set<String> collectPrefixes(List<DispatcherExtension> extensions) {
        Set<String> prefixes = new HashSet<>();

        for (DispatcherExtension extension : extensions) {
            List<String> declared = extension.getRestPrefixes();
            if (null == declared) {
                continue;
            }

            for (String prefix : declared) {
                if (null == prefix || prefix.trim().isEmpty()) {
                    continue;
                }

                String normalized = normalize(prefix.trim());
                if (prefixes.add(normalized)) {
                    Logger.i(DispatcherExtensions.class, "#collectPrefixes - Extension \""
                            + extension.getName() + "\" declares prefix: " + normalized);
                }
                else {
                    // 先注册者保留，不覆盖
                    Logger.e(DispatcherExtensions.class, "#collectPrefixes - Prefix \"" + normalized
                            + "\" is ALREADY declared by another extension, IGNORED \""
                            + extension.getName() + "\"");
                }
            }
        }

        return prefixes;
    }

    /**
     * 注册全部扩展提供的 REST 端点。
     *
     * <p>按扩展装载顺序、扩展内迭代顺序注册；路径重复时<b>先注册者保留</b>。
     * 宿主自身的端点应在本方法调用<b>之前</b>注册完毕，使宿主端点天然优先，
     * 无需任何模块侧配合。</p>
     *
     * <p>单个扩展提供端点时抛异常只记 ERROR，不影响其余扩展——
     * 一个模块的接入问题不应让网关起不来。</p>
     *
     * @param extensions 扩展列表。
     * @param httpServer 上下文处理器收集器。
     */
    public static void registerEndpointHandlers(List<DispatcherExtension> extensions,
            cube.util.HttpServer httpServer) {
        if (null == extensions || extensions.isEmpty() || null == httpServer) {
            return;
        }

        // 宿主已注册的路径：先到先得
        Set<String> paths = new HashSet<>();
        for (ContextHandler handler : httpServer.getContextHandlers()) {
            addPath(paths, handler);
        }

        int total = 0;
        for (DispatcherExtension extension : extensions) {
            List<ContextHandler> handlers;
            try {
                handlers = extension.getEndpointHandlers();
            } catch (Throwable t) {
                Logger.e(DispatcherExtensions.class, "#registerEndpointHandlers - Extension \""
                        + extension.getName() + "\" FAILED on providing endpoints",
                        (t instanceof Exception) ? (Exception) t : null);
                continue;
            }

            if (null == handlers || handlers.isEmpty()) {
                continue;
            }

            int count = 0;
            for (ContextHandler handler : handlers) {
                if (null == handler) {
                    continue;
                }

                // getContextPath 可为 null（挂在根路径），此时不做去重
                String path = handler.getContextPath();
                if (null != path && !paths.add(path)) {
                    Logger.e(DispatcherExtensions.class, "#registerEndpointHandlers - Path \"" + path
                            + "\" is ALREADY registered, IGNORED \"" + extension.getName() + "\"");
                    continue;
                }

                handler.setAttribute(cube.dispatcher.monitor.RestMetricsListener.MODULE_ATTRIBUTE, extension.getName());
                httpServer.addContextHandler(handler);
                ++count;
                ++total;
            }

            Logger.i(DispatcherExtensions.class, "#registerEndpointHandlers - Extension \""
                    + extension.getName() + "\" registered " + count + " endpoint(s)");
        }

        if (total > 0) {
            Logger.i(DispatcherExtensions.class, "#registerEndpointHandlers - "
                    + "Module endpoints total: " + total);
        }
    }

    /**
     * 实例化单个扩展。
     *
     * @param className 实现类全限定名。
     * @return 返回实例；失败时返回 {@code null}。
     */
    private static DispatcherExtension instantiate(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();

            if (!(instance instanceof DispatcherExtension)) {
                Logger.e(DispatcherExtensions.class, "#instantiate - \"" + className
                        + "\" is NOT a DispatcherExtension");
                return null;
            }

            Logger.i(DispatcherExtensions.class, "#instantiate - Loaded extension: " + className);
            return (DispatcherExtension) instance;
        } catch (Throwable t) {
            // 捕获 Throwable：扩展由模块侧维护，类缺失等 Error 不应让网关启动失败
            Logger.e(DispatcherExtensions.class, "#instantiate - FAILED: " + className,
                    (t instanceof Exception) ? (Exception) t : null);
            return null;
        }
    }

    /**
     * 登记一个处理器占用的路径。
     *
     * @param paths 已登记的路径集合。
     * @param handler 处理器。
     */
    private static void addPath(Set<String> paths, ContextHandler handler) {
        if (null != handler && null != handler.getContextPath()) {
            paths.add(handler.getContextPath());
        }
    }

    /**
     * 归一化前缀：去掉尾斜杠。
     *
     * @param prefix 原始前缀。
     * @return 返回归一化后的前缀。
     */
    private static String normalize(String prefix) {
        String normalized = prefix;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 获取只读的空列表，供调用方兜底。
     *
     * @return 返回空列表。
     */
    public static List<ContextHandler> none() {
        return Collections.emptyList();
    }
}
