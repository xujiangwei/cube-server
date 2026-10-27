/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.processor;

import cell.util.log.Logger;

import java.io.*;
import java.nio.file.Path;

/**
 * 处理器基类。
 */
public abstract class Processor {

    private Path workPath;

    public Processor(Path workPath) {
        this.workPath = workPath;
    }

    /**
     * 获取工作目录。
     *
     * @return 临时文件所在目录。
     */
    public Path getWorkPath() {
        return this.workPath;
    }

    /**
     * 构造输入流读取任务。
     *
     * <p>在独立线程中持续读取流并写入上下文，避免子进程阻塞。</p>
     *
     * @param inputStream 输入流。
     * @param context     处理上下文。
     * @return 可提交给执行器的任务。
     */
    protected Runnable buildInputStreamWorker(InputStream inputStream, ProcessorContext context) {
        Runnable worker = new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line = null;
                try {
                    while ((line = reader.readLine()) != null) {
                        context.appendStdOutput(line);
                    }
                } catch (Exception e) {
                    Logger.w(Processor.class, "#buildInputStreamWorker", e);
                } finally {
                    if (null != reader) {
                        try {
                            reader.close();
                        } catch (IOException e) {
                            // 释放失败不影响主流程，仅记录
                            Logger.d(Processor.class, "#buildInputStreamWorker - Failed to close 'reader': " + e.getMessage());
                        }
                    }
                }
            }
        };

        return worker;
    }

    /**
     * 执行处理。
     *
     * @param context 处理上下文。
     */
    public abstract void go(ProcessorContext context);
}
