/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.fileprocessor.processor;

import cell.util.log.Logger;
import cube.common.entity.FileLabel;
import cube.common.entity.FileResult;
import cube.processor.ProcessorContext;
import cube.processor.Subprocess;
import cube.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR 处理器。
 */
public class OCRProcessor extends OpticalCharacterRecognition {

    /**
     * 单页 OCR 超时：3 分钟。
     */
    private final static long OCR_TIMEOUT_MILLIS = 3 * 60 * 1000L;

    private FileLabel imageFileLabel;

    public OCRProcessor(Path workPath) {
        super(workPath);
    }

    public void setImageFileLabel(FileLabel imageFileLabel) {
        this.imageFileLabel = imageFileLabel;
    }

    private boolean check() {
        if (null == this.inputImage) {
            return false;
        }

        if (null == this.outputText) {
            String name = FileUtils.extractFileName(this.inputImage.getName());
            this.outputText = new File(this.getWorkPath().toString(), name);
        }

        return true;
    }

    @Override
    public void go(ProcessorContext context) {
        if (!this.check()) {
            Logger.w(OCRProcessor.class, "Check failed");
            return;
        }

        OCRProcessorContext processorContext = (OCRProcessorContext) context;

        List<String> commandLine = new ArrayList<>();
        commandLine.add("tesseract");
        commandLine.add(this.inputImage.getAbsolutePath());
        commandLine.add(this.outputText.getAbsolutePath());

        commandLine.add("-l");
        commandLine.add(processorContext.getLanguage());

        if (processorContext.isSingleLine()) {
            commandLine.add("--psm");
            commandLine.add("7");
        }

        commandLine.add("hocr");

        Subprocess.Result result = Subprocess.execute(commandLine, getWorkPath().toFile(),
                OCR_TIMEOUT_MILLIS);

        // 只有退出码 0 才是成功：原实现用 (-1 != status)，任何退出码都算成功
        if (!result.isSuccess()) {
            Logger.w(OCRProcessor.class, "Process error: " + result);
            return;
        }

        processorContext.setSuccessful(true);
        processorContext.setImageFileLabel(this.imageFileLabel);
        processorContext.readResult(this.outputText);

        File outputFile = new File(this.getWorkPath().toFile(), this.outputText.getName() + ".ocr");
        if (!outputFile.exists()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logger.w(OCRProcessor.class, "Interrupted while waiting for the OCR output");
            }
        }

        try {
            processorContext.getOcrFile().outputFile(new FileOutputStream(outputFile));
            FileResult result2 = new FileResult(outputFile);
            processorContext.addResult(result2);
        } catch (IOException e) {
            Logger.e(OCRProcessor.class, "#go - Failed to write OCR output", e);
        }
    }
}
