/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.processor;

import cell.util.log.Logger;
import cube.util.ConfigUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FFMPEG 工具。
 */
public abstract class FFmpeg extends Processor {

    /**
     * 探测媒体属性的超时：60 秒。
     */
    private final static long PROBE_TIMEOUT_MILLIS = 60 * 1000L;

    /**
     * 执行转码的超时：10 分钟。
     *
     * <p>视频转码耗时与文件大小、码率强相关，这里给一个宽松上界；超时后进程会被强制终止，
     * 避免调用线程永久挂起。</p>
     */
    private final static long CALL_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    private AtomicBoolean running;

    private String ffmpegCommand;
    private String ffprobeCommand;

    public FFmpeg(Path workPath) {
        super(workPath);
        this.running = new AtomicBoolean(false);
        this.ffmpegCommand = "/usr/bin/ffmpeg";
        this.ffprobeCommand = "/usr/bin/ffprobe";

        try {
            File config = new File("config/ffmpeg.properties");
            if (!config.exists()) {
                config = new File("ffmpeg.properties");
            }

            if (config.exists()) {
                Properties properties = ConfigUtils.readProperties(config.getAbsolutePath());
                this.ffmpegCommand = properties.getProperty("ffmpeg", "/usr/bin/ffmpeg");
                this.ffprobeCommand = properties.getProperty("ffprobe", "/usr/bin/ffprobe");
                Logger.i(getClass(), "FFmpeg: " + this.ffmpegCommand);
            }
            else {
                Logger.w(getClass(), "Can NOT find ffmpeg config properties file, use default config: \"" +
                        this.ffmpegCommand + "\" and \"" + this.ffprobeCommand + "\"");
            }
        } catch (IOException e) {
            Logger.w(getClass(), "#FFmpeg", e);
        }
    }

    public boolean isRunning() {
        return this.running.get();
    }

    /**
     * 探测媒体文件属性。
     *
     * @param filePath
     * @return
     */
    protected JSONObject probe(String filePath) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(this.ffprobeCommand);
        commandLine.add("-v");
        commandLine.add("quiet");
        commandLine.add("-show_format");
        commandLine.add("-show_streams");
        commandLine.add("-print_format");
        commandLine.add("json");
        commandLine.add(filePath);

        this.running.set(true);
        Subprocess.Result result;
        try {
            result = Subprocess.execute(commandLine, getWorkPath().toFile(), PROBE_TIMEOUT_MILLIS);
        } finally {
            this.running.set(false);
        }

        if (!result.isSuccess()) {
            Logger.w(this.getClass(), "#probe - Failed to probe: " + filePath + " - " + result);
            return null;
        }

        StringBuilder buf = new StringBuilder();
        for (String line : result.getOutput()) {
            buf.append(line.trim()).append("\n");
        }

        try {
            return new JSONObject(buf.toString());
        } catch (JSONException e) {
            Logger.w(this.getClass(), "#probe - Invalid JSON output: " + filePath, e);
            return null;
        }
    }

    protected boolean call(List<String> params, ProcessorContext context) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(this.ffmpegCommand);
        commandLine.addAll(params);

        this.running.set(true);
        Subprocess.Result result;
        try {
            result = Subprocess.execute(commandLine, getWorkPath().toFile(), CALL_TIMEOUT_MILLIS);
        } finally {
            this.running.set(false);
        }

        if (null != context) {
            for (String line : result.getOutput()) {
                context.appendStdOutput(line);
            }
        }

        if (!result.isSuccess()) {
            Logger.w(this.getClass(), "#call - Failed: " + this.ffmpegCommand + " - " + result);
            return false;
        }

        return true;
    }
}
