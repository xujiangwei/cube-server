/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIGC 插件的 SPI 版本一致性校验（构建期闸门）。
 *
 * <p><b>为什么需要它</b>：{@code AIGCSPI.VERSION} 是 {@code final static int}，
 * javac 会把它<b>内联</b>进插件字节码。这个内联是必需的——若改成普通字段或方法，
 * 旧插件在运行期读到的是<b>宿主</b>的当前值，宿主侧的兼容性检查将恒真而彻底失效。
 * 代价是：插件一旦没有对着当前 common 重新编译，就会永久带着旧版本号，
 * 而宿主只会在启动时回一行 {@code requires SPI version N ... rejected}，
 * 模块整体不装载。本类把这个"运行期才发现"提前到"构建期失败"。</p>
 *
 * <p><b>三道校验</b>（任一不通过即退出码 1）：</p>
 * <ol>
 *     <li>源码 {@code AIGCSPI.java} 里的 {@code VERSION} 与运行时 classpath 上
 *         {@code AIGCSPI} 的实际值一致——防止编译用的 common jar 是旧的；</li>
 *     <li>插件 {@code getDescriptor().spiVersion} 与该值一致——防止插件产物是旧的；</li>
 *     <li>宿主 {@code AIGCSPI.isCompatible(declared)} 返回 {@code true}。</li>
 * </ol>
 *
 * <p><b>为何用反射读常量</b>：本类与 {@code AIGCSPI} 同在 common，
 * 直接写 {@code AIGCSPI.VERSION} 会被同样内联成编译期字面量，
 * 那就只能校验"编译本类的那个值"，毫无意义。必须经 {@link Field#get} 取值。</p>
 *
 * <p><b>用法</b>：{@code java cube.test.SpiVersionCheck <模块全限定类名> <AIGCSPI.java 路径>}，
 * classpath 需包含插件产物、common jar 及其依赖。退出码 0 表示一致。</p>
 */
public final class SpiVersionCheck {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("VERSION\\s*=\\s*(\\d+)\\s*;");

    private SpiVersionCheck() {
    }

    public static void main(String[] args) {
        if (2 != args.length) {
            System.err.println("[SPI] 用法: SpiVersionCheck <模块全限定类名> <AIGCSPI.java 路径>");
            System.exit(2);
        }

        String moduleName = args[0].trim();
        File source = new File(args[1]);

        int sourceVersion;
        int runtimeVersion;
        try {
            sourceVersion = readSourceVersion(source);
            runtimeVersion = readRuntimeVersion();
        } catch (Exception e) {
            System.err.println("[SPI] 读取版本失败: " + e);
            System.exit(1);
            return;
        }

        System.out.println("[SPI] 源码 " + sourceVersion
                + " | classpath 上的 common " + runtimeVersion
                + " | 模块 " + moduleName);

        if (sourceVersion != runtimeVersion) {
            System.err.println("[SPI] 失败: 编译期 common jar 与源码不一致（jar=" + runtimeVersion
                    + ", 源码=" + sourceVersion + "）。先重新构建 common，再构建本模块。");
            System.exit(1);
        }

        int declared;
        try {
            declared = readModuleSpiVersion(moduleName);
        } catch (Throwable t) {
            System.err.println("[SPI] 失败: 无法读取模块的 spiVersion —— " + t
                    + "（产物缺失，或依赖不在 classpath 上）");
            System.exit(1);
            return;
        }

        System.out.println("[SPI] 模块 " + moduleName + " 声明 " + declared);

        if (declared != sourceVersion) {
            System.err.println("[SPI] 失败: 模块产物是旧的（声明 " + declared
                    + ", 期望 " + sourceVersion + "）。该模块必须对着当前 common 重新编译；"
                    + "宿主会以 \"requires SPI version " + declared + " ... rejected\" 拒绝装载。");
            System.exit(1);
        }

        if (!isCompatible(declared)) {
            System.err.println("[SPI] 失败: 宿主不接受 SPI 版本 " + declared
                    + "（当前 " + sourceVersion + "）");
            System.exit(1);
        }

        System.out.println("[SPI] 通过: " + moduleName + " 与 common 的 SPI 版本一致（"
                + sourceVersion + "）");
    }

    /**
     * 从源码读取 {@code VERSION}，避免依赖已被内联的常量。
     */
    private static int readSourceVersion(File source) throws Exception {
        if (!source.isFile()) {
            throw new IllegalStateException("文件不存在: " + source.getAbsolutePath());
        }

        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = VERSION_PATTERN.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("未找到 VERSION 定义: " + source.getAbsolutePath());
        }

        return Integer.parseInt(matcher.group(1));
    }

    /**
     * 反射读取 classpath 上 {@code AIGCSPI.VERSION} 的实际值。
     */
    private static int readRuntimeVersion() throws Exception {
        Class<?> clazz = Class.forName("cube.aigc.spi.AIGCSPI");
        Field field = clazz.getField("VERSION");
        return ((Number) field.get(null)).intValue();
    }

    /**
     * 实例化模块并读取其描述符里声明的 SPI 版本。
     */
    private static int readModuleSpiVersion(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        Object module = clazz.getDeclaredConstructor().newInstance();
        Method getDescriptor = clazz.getMethod("getDescriptor");
        Object descriptor = getDescriptor.invoke(module);
        if (null == descriptor) {
            throw new IllegalStateException("getDescriptor() 返回 null");
        }

        Field spiVersion = descriptor.getClass().getField("spiVersion");
        return spiVersion.getInt(descriptor);
    }

    private static boolean isCompatible(int version) {
        try {
            Class<?> clazz = Class.forName("cube.aigc.spi.AIGCSPI");
            Method method = clazz.getMethod("isCompatible", int.class);
            return Boolean.TRUE.equals(method.invoke(null, version));
        } catch (Exception e) {
            System.err.println("[SPI] 警告: 调用 isCompatible 失败，跳过该校验: " + e);
            return true;
        }
    }
}
