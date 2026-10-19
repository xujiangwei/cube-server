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

/** Exercises configuration discovery in a separate JVM with its own working directory. */
public final class HarnessConfigTest {
    private static void write(Path path, boolean valid) throws Exception {
        Files.write(path, Arrays.asList(
                "# 测试私有配置；不加载其他模板。",
                "worker.credential=" + (valid ? "private-worker-credential-for-config-test" : ""),
                "api.credential=private-api-credential-for-config-test"), StandardCharsets.UTF_8);
    }

    private static void probe(Path directory, Path override, String expectedError) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path")
                        .split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Paths.get(entry).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (override != null) command.add("-Dharness.config=" + override);
        command.addAll(Arrays.asList("-cp", classpath, HarnessConfigTest.class.getName(), "probe"));
        Process child = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        try {
            if (!child.waitFor(15, TimeUnit.SECONDS))
                throw new AssertionError("Configuration probe timed out");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = child.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            String output = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (expectedError == null) {
                if (child.exitValue() != 0 || !output.contains("CONFIG_LOADED"))
                    throw new AssertionError("Configuration did not load: " + output);
            } else if (child.exitValue() == 0 || !output.contains(expectedError)) {
                throw new AssertionError("Expected " + expectedError + ": " + output);
            }
        } finally {
            child.destroyForcibly();
        }
    }

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
                while (iterator.hasNext()) Files.delete(iterator.next());
            }
        }
    }
}
