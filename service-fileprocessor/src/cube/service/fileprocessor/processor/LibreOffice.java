/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.fileprocessor.processor;

import cell.util.log.Logger;
import cube.processor.Processor;
import cube.processor.ProcessorContext;
import cube.processor.Subprocess;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Libre Office 操作处理器。
 */
public abstract class LibreOffice extends Processor {

    /**
     * 文档转换超时：3 分钟。
     */
    private final static long CONVERT_TIMEOUT_MILLIS = 3 * 60 * 1000L;

    private AtomicBoolean running;

    public LibreOffice(Path workPath) {
        super(workPath);
        this.running = new AtomicBoolean(false);
    }

    protected boolean callConvertTo(String outputFormat, String inputFile, ProcessorContext context) {
        List<String> params = new ArrayList<>();
        params.add("--convert-to");
        params.add(outputFormat);
        params.add(inputFile);
        return this.call(params, context);
    }

    protected boolean call(List<String> params, ProcessorContext context) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add("libreoffice");
        commandLine.addAll(params);

        this.running.set(true);
        Subprocess.Result result;
        try {
            result = Subprocess.execute(commandLine, getWorkPath().toFile(), CONVERT_TIMEOUT_MILLIS);
        } finally {
            this.running.set(false);
        }

        if (null != context) {
            for (String line : result.getOutput()) {
                context.appendStdOutput(line);
            }
        }

        // 只有退出码 0 才是成功：原实现用 (-1 != status)，任何退出码都算成功
        if (!result.isSuccess()) {
            Logger.w(this.getClass(), "#call - Conversion failed: " + result);
            return false;
        }

        return true;
    }
}
