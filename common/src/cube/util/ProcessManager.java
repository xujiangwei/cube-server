/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

import cell.util.log.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程管理器。
 *
 * @deprecated 不要使用。本类存在三处设计缺陷：用固定 16 线程池做阻塞读流（并发进程数一高就会互相饿死）、
 * {@code waitFor()} 没有超时（子进程挂死会永久占用调用线程）、输出无上限累积。
 * 新的子进程调用请使用 {@code cube.processor.Subprocess}（合并流 + 独立读线程 + 超时强杀 + 仅 0 判成功）。
 * 本类仓库内已无调用点（唯一的引用是 {@code Kernel#shutdown} 里的 {@code destroy()}），
 * 保留只为二进制兼容，后续版本删除。
 */
@Deprecated
public final class ProcessManager {

    private final static ProcessManager instance = new ProcessManager();

    private ExecutorService executor;

    private ProcessManager() {
        this.executor = Executors.newFixedThreadPool(16);
    }

    public final static ProcessManager getInstance() {
        return ProcessManager.instance;
    }

    public void destroy() {
        this.executor.shutdown();
    }

    public ProcessOutput run(List<String> commandLine) {
        return this.run(commandLine, false);
    }

    public ProcessOutput run(List<String> commandLine, boolean outputError) {
        ProcessBuilder pb = new ProcessBuilder(commandLine);

        ProcessOutput output = null;
        Process process = null;

        try {
            // 启动进程
            process = pb.start();

            output = new ProcessOutput(process);
            // 监听输入流
            output.monitorInputStream();

            if (outputError) {
                output.monitorErrorStream();
            }

            try {
                output.status = process.waitFor();
            } catch (InterruptedException e) {
                // 记录中断状态，恢复中断标记交由上层处理
                Logger.w(ProcessManager.class, "#run - Interrupted while waiting", e);
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            Logger.e(ProcessManager.class, "#run - I/O error", e);
        } finally {
            process = null;
        }

        return output;
    }

    public class ProcessOutput {

        protected int status = 1;

        /**
         * 0 - 未执行
         * 1 - 正在执行
         * 2 - 结束
         */
        protected int phase = 0;

        protected Process process;

        protected BufferedReader stdInput = null;

        protected BufferedReader errorInput = null;

        private List<String> stdInputList = null;

        private List<String> errorInputList = null;

        protected ProcessOutput(Process process) {
            this.process = process;
        }

        protected void monitorInputStream() {
            this.stdInputList = new ArrayList<>();
            this.stdInput = new BufferedReader(new InputStreamReader(this.process.getInputStream()));

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    phase = 1;

                    try {
                        String line = null;
                        while ((line = stdInput.readLine()) != null) {
                            if (line.length() > 0) {
                                stdInputList.add(line);
                            }
                        }
                    } catch (IOException e) {
                        Logger.e(ProcessManager.class, "#run - I/O error", e);
                    } finally {
                        try {
                            stdInput.close();
                        } catch (IOException e) {
                            Logger.e(ProcessManager.class, "#run - I/O error", e);
                        }

                        phase = 2;
                    }
                }
            });

        }

        protected void monitorErrorStream() {
            this.errorInputList = new ArrayList<>();
            this.errorInput = new BufferedReader(new InputStreamReader(this.process.getErrorStream()));

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line = null;
                        while ((line = errorInput.readLine()) != null) {
                            if (line.length() > 0) {
                                errorInputList.add(line);
                            }
                        }
                    } catch (IOException e) {
                        Logger.e(ProcessManager.class, "#run - I/O error", e);
                    } finally {
                        try {
                            errorInput.close();
                        } catch (IOException e) {
                            Logger.e(ProcessManager.class, "#run - I/O error", e);
                        }

                        phase = 2;
                    }
                }
            });
        }

        public int getStatus() {
            return this.status;
        }

        public boolean isFinish() {
            return this.phase == 2;
        }

        public List<String> getStdInput() {
            return this.stdInputList;
        }

        public List<String> getErrorInput() {
            return this.errorInputList;
        }
    }
}
