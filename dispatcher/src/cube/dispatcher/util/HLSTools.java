/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.util;

import cell.util.log.Logger;
import cube.processor.Subprocess;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * HLS 工具。
 */
public final class HLSTools {

    /**
     * 版本探测超时：15 秒。
     */
    private final static long VERSION_TIMEOUT_MILLIS = 15 * 1000L;

    /**
     * 切片超时：10 分钟（与 FFmpeg 转码一致）。
     */
    private final static long HLS_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    private static String ffmpeg = null;

    private HLSTools() {
    }

    private static String loadFFmpegPath() {
        File configFile = new File("HLSTools.properties");
        if (!configFile.exists()) {
            configFile = new File("config/HLSTools.properties");
            if (!configFile.exists()) {
                return null;
            }
        }

        String path = null;

        FileInputStream fis = null;

        try {
            fis = new FileInputStream(configFile);
            Properties properties = new Properties();
            properties.load(fis);
            path = properties.getProperty("ffmpeg");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != fis) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }

        if (null != path) {
            ffmpeg = path;
        }

        return path;
    }

    /**
     * 校验 HLS 工具是否可用。
     *
     * <p>先解析 ffmpeg 路径，再执行 {@code ffmpeg -version} 确认可运行；
     * 任一环节失败都返回 {@code false} 并记录告警。</p>
     *
     * @return 可用返回 {@code true} 。
     */
    public static boolean checkEnabled() {
        String ffmpegPath = loadFFmpegPath();
        if (null == ffmpegPath) {
            return false;
        }

        Subprocess.Result result = Subprocess.execute(
                Arrays.asList(ffmpegPath, "-version"), null, VERSION_TIMEOUT_MILLIS);

        // ffmpeg -version 的正常退出码是 0
        if (!result.isSuccess()) {
            Logger.w(HLSTools.class, "#checkEnabled - " + result);
            return false;
        }

        return true;
    }

    /**
     * 把媒体文件切片为 HLS。
     *
     * <p>调用 ffmpeg 生成 M3U8 与 TS 分片，输出到指定目录；
     * 命令通过受控子进程执行并带超时，失败只返回 {@code false} 不抛异常。</p>
     *
     * @param workPath   切片输出目录。
     * @param inputFile  源媒体文件。
     * @param outputFile 期望生成的 M3U8 文件。
     * @return 转码成功返回 {@code true} 。
     */
    public static boolean toHLS(File workPath, File inputFile, File outputFile) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(null != ffmpeg ? ffmpeg : "ffmpeg");
        commandLine.add("-re");
        commandLine.add("-i");
        commandLine.add(inputFile.getName());
        commandLine.add("-c");
        commandLine.add("copy");
        commandLine.add("-f");
        commandLine.add("hls");
        commandLine.add("-hls_time");
        commandLine.add("10");
        commandLine.add("-hls_list_size");
        commandLine.add("0");
        commandLine.add("-bsf:v");
        commandLine.add("h264_mp4toannexb");
        commandLine.add(outputFile.getName());

        Subprocess.Result result = Subprocess.execute(commandLine, workPath, HLS_TIMEOUT_MILLIS);

        if (result.isSuccess()) {
            return true;
        }
        else {
            Logger.w(HLSTools.class, "#toHLS - " + result);
            return false;
        }
    }
}
