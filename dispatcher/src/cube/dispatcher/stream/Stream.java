/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.stream;

/**
 * 流数据包。
 *
 * <p>承载一次 HTTP 流式响应中的一个分片：分片类型、归属流名称、序号、时间戳与原始字节。
 * 序号用于下游按序拼接，乱序到达时由消费方负责重排。</p>
 */
public class Stream {

    /** 分片类型标识，由 {@link StreamType#parse(String)} 解析。 */
    public final String type;

    /** 所属流的名称。 */
    public final String name;

    /** 分片序号，从 0 开始递增。 */
    public final int index;

    /** 分片数据。 */
    public final byte[] data;

    /** 分片生成时间戳，单位毫秒。 */
    public final long timestamp;

    public Stream(String type, String name, int index, long timestamp, byte[] data) {
        this.type = type;
        this.name = name;
        this.index = index;
        this.timestamp = timestamp;
        this.data = data;
    }

    /**
     * 获取分片类型枚举。
     *
     * @return 类型枚举；标识无法识别时返回 {@code null} 。
     */
    public StreamType getType() {
        return StreamType.parse(this.type);
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * <p>只输出元信息与数据长度，不打印数据内容。</p>
     *
     * @return 形如 {@code 类型|名称|序号|时间戳|(长度)...} 的字符串。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(this.type);
        buf.append("|");
        buf.append(this.name);
        buf.append("|");
        buf.append(this.index);
        buf.append("|");
        buf.append(this.timestamp);
        buf.append("|");
        buf.append("(");
        buf.append(this.data.length);
        buf.append(")...");
        return buf.toString();
    }
}
