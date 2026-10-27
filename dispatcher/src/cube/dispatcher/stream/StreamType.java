/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.stream;

/**
 * 流分片类型。
 *
 * <p>标识分片的业务含义，接收方据此选择处理链路；未知类型的分片会被丢弃。</p>
 */
public enum StreamType {

    /**
     * 语音分割及分析。
     */
    SpeakerDiarization("SpeakerDiarization"),

    /**
     * 停止。
     */
    Stop("Stop"),

    /**
     * 语音识别。
     */
    // SpeechRecognition("SpeechRecognition")

    ;

    /** 协议中的类型名称，与枚举名保持一致。 */
    public final String name;

    StreamType(String name) {
        this.name = name;
    }

    /**
     * 解析类型名称。
     *
     * <p>同时接受枚举名与协议名，忽略大小写。</p>
     *
     * @param name 类型名称。
     * @return 对应枚举；无法识别时返回 {@code null} 。
     */
    public static StreamType parse(String name) {
        for (StreamType type : StreamType.values()) {
            if (type.name.equalsIgnoreCase(name) || type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
