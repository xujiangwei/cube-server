/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.test;

import cube.processor.Subprocess;

import java.util.Arrays;

/**
 * {@link Subprocess} 的主函数式自检。
 *
 * <p>工程未引入 JUnit，沿用 {@code cube/test} 目录里既有的 main 风格。覆盖四条契约：</p>
 * <ol>
 *     <li>超时必须强杀并返回 {@code timedOut=true}；</li>
 *     <li>非零退出码必须判定为失败；</li>
 *     <li>stderr 必须合并进输出（否则子进程会阻塞在写）；</li>
 *     <li>可执行文件不存在时必须返回"未启动"而不是抛异常。</li>
 * </ol>
 */
public final class SubprocessTest {

    private static int failed = 0;

    private SubprocessTest() {
    }

    public static void main(String[] args) {
        testTimeout();
        testExitCode();
        testMergedStream();
        testMissingBinary();

        if (0 == failed) {
            System.out.println("SubprocessTest PASS");
        }
        else {
            System.out.println("SubprocessTest FAIL - " + failed + " case(s)");
            System.exit(1);
        }
    }

    private static void testTimeout() {
        long begin = System.currentTimeMillis();
        Subprocess.Result result = Subprocess.execute(
                Arrays.asList("/bin/sh", "-c", "echo hello; sleep 10"), null, 800);
        long elapsed = System.currentTimeMillis() - begin;

        check("timeout:timedOut", result.isTimedOut());
        check("timeout:notSuccess", !result.isSuccess());
        check("timeout:elapsed<5s", elapsed < 5000);
        check("timeout:output", result.getOutput().contains("hello"));
    }

    private static void testExitCode() {
        Subprocess.Result result = Subprocess.execute(
                Arrays.asList("/bin/sh", "-c", "exit 1"), null, 5000);

        check("exitCode:notSuccess", !result.isSuccess());
        check("exitCode:value", 1 == result.getExitCode());
    }

    private static void testMergedStream() {
        Subprocess.Result result = Subprocess.execute(
                Arrays.asList("/bin/sh", "-c", "echo out; echo err >&2; exit 0"), null, 5000);

        check("merge:success", result.isSuccess());
        check("merge:stdout", result.getOutput().contains("out"));
        check("merge:stderr", result.getOutput().contains("err"));
    }

    private static void testMissingBinary() {
        Subprocess.Result result = Subprocess.execute(
                Arrays.asList("/no/such/binary-for-subprocess-test"), null, 5000);

        check("missing:notStarted", !result.isStarted());
        check("missing:notSuccess", !result.isSuccess());
        check("missing:error", null != result.getError());
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            ++failed;
            System.out.println("  [FAIL] " + name);
        }
        else {
            System.out.println("  [ OK ] " + name);
        }
    }
}
