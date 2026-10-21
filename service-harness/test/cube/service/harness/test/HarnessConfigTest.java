package cube.service.harness.test;

import cube.service.harness.HarnessConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Harness 配置发现集成测试：在独立 JVM 及其自己的工作目录中验证默认 config 目录发现、
 * 显式覆盖、缺失文件与私有凭据校验。
 *
 * <p>每个场景都通过子进程重新执行本类的 probe 模式，确保结论来自全新的配置加载，
 * 而不是父进程已缓存的状态；预期成功时子进程输出 {@code CONFIG_LOADED} 且退出码为 0，
 * 预期失败时输出必须包含给定错误信息片段且退出码非零。
 */
public final class HarnessConfigTest {
    /**
     * 向指定路径写入仅含 worker.credential 的私有配置文件，不加载任何其他模板。
     *
     * @param path 目标配置文件路径
     * @param valid 为 true 写入有效私有凭据；为 false 将凭据留空以触发凭据校验失败
     * @throws Exception 文件写入失败
     */
    private static void write(Path path, boolean valid) throws Exception {
        Files.write(path, Arrays.asList(
                "# 测试私有配置；不加载其他模板。",
                "worker.credential=" + (valid ? "private-worker-credential-for-config-test" : "")), StandardCharsets.UTF_8);
    }

    /**
     * 以给定工作目录启动子 JVM 执行一次配置探测，并按预期校验其输出与退出码。
     *
     * @param directory 子进程工作目录，默认配置发现相对于该目录解析
     * @param override 显式配置文件路径，经 -Dharness.config 传入；为 null 表示不覆盖
     * @param expectedError 期望出现的错误信息片段；为 null 表示期望加载成功
     * @throws Exception 子进程启动、等待或输出读取失败
     */
    private static void probe(Path directory, Path override, String expectedError) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path")
                        .split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Paths.get(entry).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (override != null) {
            command.add("-Dharness.config=" + override);
        }
        command.addAll(Arrays.asList("-cp", classpath, HarnessConfigTest.class.getName(), "probe"));
        Process child = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        try {
            if (!child.waitFor(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Configuration probe timed out");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = child.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                }
            }
            String output = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (expectedError == null) {
                if (child.exitValue() != 0 || !output.contains("CONFIG_LOADED")) {
                    throw new AssertionError("Configuration did not load: " + output);
                }
            } else if (child.exitValue() == 0 || !output.contains(expectedError)) {
                throw new AssertionError("Expected " + expectedError + ": " + output);
            }
        } finally {
            child.destroyForcibly();
        }
    }

    /**
     * 测试入口：带 probe 参数时作为子进程执行一次配置加载；无参数时驱动全部配置发现场景。
     *
     * <p>覆盖：默认 config/harness.properties 发现、缺失私有凭据、显式 override 生效、
     * override 指向缺失文件、override 凭据无效，以及仅存在 template 时不满足发现。
     *
     * @param args 为 "probe" 时进入子进程探测模式；否则为主驱动模式
     * @throws Exception 进程、文件或配置加载失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            HarnessConfig.load();
            System.out.println("CONFIG_LOADED");
            return;
        }
        Path directory = Files.createTempDirectory("harness config test ");
        try {
            Path config = Files.createDirectory(directory.resolve("config")).resolve("harness.properties");
            write(config, true);
            probe(directory, null, null);

            write(config, false);
            probe(directory, null, "Missing private credential");
            Path override = directory.resolve("custom config.properties");
            write(override, true);
            probe(directory, override, null);

            write(config, true);
            probe(directory, directory.resolve("missing.properties"), "NoSuchFileException");
            write(override, false);
            probe(directory, override, "Missing private credential");

            Files.delete(config);
            write(config.resolveSibling("harness.properties.template"), true);
            probe(directory, null, "NoSuchFileException");
            System.out.println("PASS: default config directory, explicit override, missing files and credential validation");
        } finally {
            try (Stream<Path> paths = Files.walk(directory)) {
                Iterator<Path> iterator = paths.sorted(Comparator.reverseOrder()).iterator();
                while (iterator.hasNext()) {
                    Files.delete(iterator.next());
                }
            }
        }
    }
}
