/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 音频格式工具。
 *
 * <p>在裸 PCM 数据与 WAV 容器之间互转，供语音识别链路使用。</p>
 */
public class AudioUtils {

    /**
     * 采样比率。
     */
    public static final int SAMPLE_RATE = 16000;

    /** 采样位深，固定 16 位。 */
    public static final int SAMPLE_SIZE_IN_BITS = 16;

    /** 声道数，固定单声道。 */
    public static final int CHANNELS = 1;

    /**
     * 把 PCM 数据封装为 WAV 。
     *
     * @param pcmData 裸 PCM 数据。
     * @return WAV 字节流。
     */
    public static byte[] pcmToWav(byte[] pcmData) {
        byte[] header = buildWavHeader(pcmData.length, SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS);
        byte[] wavData = new byte[header.length + pcmData.length];
        System.arraycopy(header, 0, wavData, 0, header.length);
        System.arraycopy(pcmData, 0, wavData, header.length, pcmData.length);
        return wavData;
    }

    /**
     * 把 PCM 数据的指定区间封装为 WAV 。
     *
     * @param pcmData 裸 PCM 数据。
     * @param offset  起始偏移。
     * @param length  数据长度。
     * @return WAV 字节流。
     */
    public static byte[] pcmToWav(byte[] pcmData, int offset, int length,
                                  int sampleRate, int sampleSizeInBits, int channels) {
        byte[] header = buildWavHeader(length, sampleRate, sampleSizeInBits, channels);
        byte[] wavData = new byte[header.length + length];
        System.arraycopy(header, 0, wavData, 0, header.length);
        System.arraycopy(pcmData, offset, wavData, header.length, length);
        return wavData;
    }

    /**
     * 从 WAV 中提取 PCM 数据。
     *
     * <p>跳过文件头，只返回音频负载部分。</p>
     *
     * @param wavData WAV 字节流。
     * @param length  期望读取的长度。
     * @return PCM 数据。
     */
    public static byte[] wavToPcm(byte[] wavData, int length) {
        return Arrays.copyOfRange(wavData, 44, length);
    }

    private static byte[] buildWavHeader(int pcmDataSize, int sampleRate, int sampleSizeInBits, int channels) {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try {
            writeString(header, "RIFF");
            writeInt(header, 36 + pcmDataSize);
            writeString(header, "WAVE");
            writeString(header, "fmt ");
            writeInt(header, 16);
            writeShort(header, 1);
            writeShort(header, channels);
            writeInt(header, sampleRate);
            writeInt(header, sampleRate * channels * (sampleSizeInBits / 8));
            writeShort(header, channels * (sampleSizeInBits / 8));
            writeShort(header, sampleSizeInBits);
            writeString(header, "data");
            writeInt(header, pcmDataSize);
        } catch (IOException e) {
            throw new RuntimeException("WAV header generation failed", e);
        }
        return header.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream stream, String text) throws IOException {
        stream.write(text.getBytes());
    }

    private static void writeInt(ByteArrayOutputStream stream, int value) throws IOException {
        stream.write(new byte[]{
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff),
                (byte) ((value >> 16) & 0xff),
                (byte) ((value >> 24) & 0xff)
        });
    }

    private static void writeShort(ByteArrayOutputStream stream, int value) throws IOException {
        stream.write(new byte[]{
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff)
        });
    }
}
