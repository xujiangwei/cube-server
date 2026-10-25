/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.processor;

import cell.util.log.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行器。
 *
 * <p>把"起进程、读输出、等结束"收敛到一处，避免各业务模块各写一份。设计约束：</p>
 * <ol>
 *     <li><b>必须合并 stderr</b>：只读一路流时，另一路写满管道缓冲区就会让子进程阻塞在写，
 *     表现为"进程永远不退出"。</li>
 *     <li><b>必须有超时</b>：没有超时的 {@code waitFor()} 会让调用线程永久挂起，
 *     在业务线程池里等同于线程泄漏。</li>
 *     <li><b>只有退出码 0 是成功</b>：{@code -1 != status}、{@code 0 == status || 1 == status}
 *     这类写法把失败当成成功，是本模块要消灭的主要缺陷。</li>
 * </ol>
 *
 * <p>读流使用独立的守护线程，不复用业务线程池——否则并发进程数一高就会互相饿死。</p>
 */
public final class Subprocess {

    /**
     * 默认超时：60 秒。
     */
    public final static long DEFAULT_TIMEOUT_MILLIS = 60 * 1000L;

    /**
     * 默认最多保留的输出行数，超出部分丢弃（只计数，不落内存）。
     */
    public final static int DEFAULT_MAX_OUTPUT_LINES = 2000;

    private Subprocess() {
    }

    /**
     * 执行命令，使用默认超时与默认输出上限。
     *
     * @param commandLine 命令行。
     * @param workDir 工作目录，允许为 {@code null} 。
     * @return 执行结果。
     */
    public static Result execute(List<String> commandLine, File workDir) {
        return execute(commandLine, workDir, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_OUTPUT_LINES);
    }

    /**
     * 执行命令。
     *
     * @param commandLine 命令行。
     * @param workDir 工作目录，允许为 {@code null} 。
     * @param timeoutMillis 超时时间（毫秒），小于等于 0 时使用默认值。
     * @return 执行结果。
     */
    public static Result execute(List<String> commandLine, File workDir, long timeoutMillis) {
        return execute(commandLine, workDir, timeoutMillis, DEFAULT_MAX_OUTPUT_LINES);
    }

    /**
     * 执行命令。
     *
     * @param commandLine 命令行。
     * @param workDir 工作目录，允许为 {@code null} 。
     * @param timeoutMillis 超时时间（毫秒），小于等于 0 时使用默认值。
     * @param maxOutputLines 输出行数上限，小于等于 0 时使用默认值。
     * @return 执行结果，永不为 {@code null} 。
     */
    public static Result execute(List<String> commandLine, File workDir, long timeoutMillis,
                                 int maxOutputLines) {
        long timeout = (timeoutMillis > 0) ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
        int limit = (maxOutputLines > 0) ? maxOutputLines : DEFAULT_MAX_OUTPUT_LINES;

        if (null == commandLine || commandLine.isEmpty()) {
            return Result.notStarted("Empty command line");
        }

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        // 关键：合并 stderr，避免只读一路流导致的管道阻塞
        builder.redirectErrorStream(true);
        if (null != workDir) {
            builder.directory(workDir);
        }

        long beginTime = System.currentTimeMillis();

        Process process = null;
        try {
            process = builder.start();
        } catch (IOException e) {
            Logger.e(Subprocess.class, "#execute - Failed to start: " + commandLine.get(0), e);
            return Result.notStarted(e.getMessage());
        }

        OutputCollector collector = new OutputCollector(process, limit);
        Thread reader = new Thread(collector, "Subprocess-Reader-" + baseName(commandLine.get(0)));
        reader.setDaemon(true);
        reader.start();

        boolean finished = false;
        boolean interrupted = false;
        int exitCode = -1;

        try {
            finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (finished) {
                exitCode = process.exitValue();
            }
        } catch (InterruptedException e) {
            interrupted = true;
            Thread.currentThread().interrupt();
        } finally {
            if (!finished) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 等待读线程收尾，避免结果里缺少最后几行
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long elapsed = System.currentTimeMillis() - beginTime;

        if (!finished) {
            Logger.w(Subprocess.class, "#execute - " + (interrupted ? "Interrupted" : "Timeout(" + timeout + "ms)")
                    + ", command: " + commandLine.get(0) + ", elapsed: " + elapsed + "ms");
        }

        return new Result(true, exitCode, !finished, elapsed, collector.getLines(), collector.getDroppedLines());
    }

    /**
     * 执行命令（可变参数形式）。
     *
     * @param command 命令行。
     * @return 执行结果。
     */
    public static Result execute(String... command) {
        return execute(Arrays.asList(command), null);
    }

    private static String baseName(String command) {
        if (null == command) {
            return "unknown";
        }

        int index = command.lastIndexOf('/');
        return (index >= 0 && index < command.length() - 1) ? command.substring(index + 1) : command;
    }

    /**
     * 读流线程：把合并后的输出按行收进内存，超过上限只计数。
     */
    private static final class OutputCollector implements Runnable {

        private final Process process;

        private final int limit;

        private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        private volatile int dropped;

        private OutputCollector(Process process, int limit) {
            this.process = process;
            this.limit = limit;
        }

        @Override
        public void run() {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(this.process.getInputStream(),
                        StandardCharsets.UTF_8));

                String line;
                while ((line = reader.readLine()) != null) {
                    if (this.lines.size() < this.limit) {
                        this.lines.add(line);
                    }
                    else {
                        ++this.dropped;
                    }
                }
            } catch (IOException e) {
                // 进程被强杀时读流会抛 IOException，属预期情况
                Logger.d(OutputCollector.class, "#run - " + e.getMessage());
            } finally {
                if (null != reader) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        Logger.d(OutputCollector.class, "#run - Failed to close reader: " + e.getMessage());
                    }
                }
            }
        }

        private List<String> getLines() {
            synchronized (this.lines) {
                return new ArrayList<>(this.lines);
            }
        }

        private int getDroppedLines() {
            return this.dropped;
        }
    }

    /**
     * 子进程执行结果。
     */
    public static final class Result {

        private final boolean started;

        private final int exitCode;

        private final boolean timedOut;

        private final long elapsedMillis;

        private final List<String> output;

        private final int droppedLines;

        private final String error;

        private Result(boolean started, int exitCode, boolean timedOut, long elapsedMillis,
                       List<String> output, int droppedLines) {
            this.started = started;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.elapsedMillis = elapsedMillis;
            this.output = output;
            this.droppedLines = droppedLines;
            this.error = null;
        }

        private Result(String error) {
            this.started = false;
            this.exitCode = -1;
            this.timedOut = false;
            this.elapsedMillis = 0L;
            this.output = new ArrayList<>();
            this.droppedLines = 0;
            this.error = error;
        }

        private static Result notStarted(String error) {
            return new Result(error);
        }

        /**
         * 进程是否成功启动并正常退出（退出码 0）。
         *
         * @return 成功返回 {@code true} 。
         */
        public boolean isSuccess() {
            return this.started && !this.timedOut && 0 == this.exitCode;
        }

        /**
         * 进程是否成功启动。
         *
         * @return 启动成功返回 {@code true} 。
         */
        public boolean isStarted() {
            return this.started;
        }

        /**
         * 是否超时（含被中断）。
         *
         * @return 超时返回 {@code true} 。
         */
        public boolean isTimedOut() {
            return this.timedOut;
        }

        /**
         * 退出码，未正常退出时为 -1。
         *
         * @return 退出码。
         */
        public int getExitCode() {
            return this.exitCode;
        }

        /**
         * 执行耗时（毫秒）。
         *
         * @return 耗时。
         */
        public long getElapsedMillis() {
            return this.elapsedMillis;
        }

        /**
         * 合并后的输出（stdout + stderr），受行数上限约束。
         *
         * @return 输出行列表，永不为 {@code null} 。
         */
        public List<String> getOutput() {
            return this.output;
        }

        /**
         * 因超过上限被丢弃的行数。
         *
         * @return 丢弃行数。
         */
        public int getDroppedLines() {
            return this.droppedLines;
        }

        /**
         * 启动失败的原因，正常启动时为 {@code null} 。
         *
         * @return 错误描述。
         */
        public String getError() {
            return this.error;
        }

        @Override
        public String toString() {
            return "Subprocess.Result{started=" + this.started + ", exitCode=" + this.exitCode
                    + ", timedOut=" + this.timedOut + ", elapsed=" + this.elapsedMillis
                    + "ms, lines=" + this.output.size() + ", dropped=" + this.droppedLines
                    + (null == this.error ? "" : ", error=" + this.error) + "}";
        }
    }
}
