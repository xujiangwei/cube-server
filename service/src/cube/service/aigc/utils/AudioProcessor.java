/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.utils;

import cell.util.log.Logger;
import cube.processor.FFmpeg;
import cube.processor.ProcessorContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * 音频处理器。
 *
 * <p>基于 ffmpeg 完成音频格式转换，当前只处理 wav 转 mp3 。</p>
 */
public class AudioProcessor extends FFmpeg {

    public AudioProcessor(Path workPath) {
        super(workPath);
    }

    /**
     * 执行转换。
     *
     * <p>按上下文类型分派；不支持的类型直接返回，不做任何处理。</p>
     *
     * @param context 处理上下文。
     */
    @Override
    public void go(ProcessorContext context) {
        if (context instanceof WavToMp3Context) {
            WavToMp3Context ctx = (WavToMp3Context) context;
            ArrayList<String> params = new ArrayList<>();
            params.add("-i");
            params.add(ctx.getInputFile());
            params.add("-vn");
            params.add("-ac");
            params.add(Integer.toString(ctx.getChannels()));
            params.add("-ar");
            params.add(Integer.toString(ctx.getSampleRate()));
            params.add("-af");
            params.add("volume=3");
            // loudnorm=I=-16
            params.add(ctx.getOutputFile());

            this.call(params, ctx);

            for (String line : ctx.getStdOutput()) {
                System.out.println("@AudioProcessor$ " + line);
            }

            boolean success = Files.exists(Paths.get(this.getWorkPath().toString(), ctx.getOutputFile()));
            ctx.setSuccessful(success);
        }
        else {
            Logger.w(this.getClass(), "#go - Unknown context");
        }
    }
}
