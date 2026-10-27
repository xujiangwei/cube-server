/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */


package cube.service.aigc.utils;

import cube.processor.ProcessorContext;
import org.json.JSONObject;

/**
 * WAV 转 MP3
 */
public class WavToMp3Context extends ProcessorContext {

    private String inputFile;

    private String outputFile;

    private int sampleRate;

    private int channels;

    public WavToMp3Context(String inputFile, String outputFile, int sampleRate, int channels) {
        this.inputFile = inputFile;
        this.outputFile = outputFile;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * 获取源文件路径。
     *
     * @return 输入文件路径。
     */
    public String getInputFile() {
        return this.inputFile;
    }

    /**
     * 获取输出文件路径。
     *
     * @return 输出文件路径。
     */
    public String getOutputFile() {
        return this.outputFile;
    }

    /**
     * 获取采样率。
     *
     * @return 采样率，单位赫兹。
     */
    public int getSampleRate() {
        return this.sampleRate;
    }

    /**
     * 获取声道数。
     *
     * @return 声道数。
     */
    public int getChannels() {
        return this.channels;
    }

    /**
     * 序列化为 JSON 。
     *
     * <p>转码上下文只在进程内使用，不参与序列化，固定返回 {@code null} 。</p>
     *
     * @return 固定返回 {@code null} 。
     */
    @Override
    public JSONObject toJSON() {
        return null;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>同上，固定返回 {@code null} 。</p>
     *
     * @return 固定返回 {@code null} 。
     */
    @Override
    public JSONObject toCompactJSON() {
        return null;
    }
}
