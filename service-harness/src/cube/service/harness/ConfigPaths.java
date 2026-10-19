/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 私有配置加载侧的路径归一化：把相对路径解析为绝对路径，并补齐缺失的目录。
 * 不改动各组件的拒绝式校验——归一化只消除"库与仓库位置随启动目录漂移"的歧义，
 * 符号链接、越界、身份不匹配等仍由 {@code StoreConfig}、{@code RuntimeSupport.safeRoot} 按原规则拒绝。
 * 相对路径按进程工作目录解析，与默认配置路径 {@code config/harness.properties} 的定位基准一致，
 * 也与宿主服务自身 {@code storage/} 目录的习惯一致；绝对路径原样保留。
 */
public final class ConfigPaths {
    private ConfigPaths() {
    }

    /**
     * 就地归一化私有配置中的目录类路径键，缺失目录自动创建：
     * <ul>
     * <li>{@code store.sqlite.root} 与 {@code store.sqlite.file}（{@code store.dialect=sqlite} 时）</li>
     * <li>{@code execution.repository}（{@code execution.enabled=true} 时）</li>
     * </ul>
     * 键缺失或为空时保持原样，交由后续校验拒绝。
     *
     * @param p 已加载的私有配置，方法会就地覆写上述路径键
     * @throws IOException 创建目录失败
     */
    public static void normalize(Properties p) throws IOException {
        if ("sqlite".equals(p.getProperty("store.dialect", "")))
            normalizeStore(p);
        if (Boolean.parseBoolean(p.getProperty("execution.enabled", "false")))
            normalizeDirectory(p, "execution.repository");
    }

    private static void normalizeStore(Properties p) throws IOException {
        String rootValue = p.getProperty("store.sqlite.root", "");
        String fileValue = p.getProperty("store.sqlite.file", "");
        if (rootValue.isEmpty() || fileValue.isEmpty()) return;

        Path root = Paths.get(rootValue).toAbsolutePath().normalize();
        Path file = Paths.get(fileValue).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path parent = file.getParent();
        // 仅创建位于专用 root 之下的父目录；越界路径留给 StoreConfig 拒绝。
        if (parent != null && parent.startsWith(root)) Files.createDirectories(parent);

        p.setProperty("store.sqlite.root", root.toString());
        p.setProperty("store.sqlite.file", file.toString());
    }

    private static void normalizeDirectory(Properties p, String key) throws IOException {
        String value = p.getProperty(key, "");
        if (value.isEmpty()) return;
        Path path = Paths.get(value).toAbsolutePath().normalize();
        Files.createDirectories(path);
        p.setProperty(key, path.toString());
    }
}
