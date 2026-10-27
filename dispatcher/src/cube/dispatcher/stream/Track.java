/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.stream;

import cell.core.net.Message;
import cell.core.net.NonblockingAcceptor;
import cell.core.net.Session;
import cell.util.log.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 流轨道。
 *
 * <p>封装一条已建立的连接，按 {@code 类型|分隔符|数据} 的协议格式把分片写回对端。
 * 写入失败会关闭并回收连接，避免半死连接继续占用资源。</p>
 */
public class Track {

    private NonblockingAcceptor acceptor;

    private Session session;

    public Track(NonblockingAcceptor acceptor, Session session) {
        this.acceptor = acceptor;
        this.session = session;
    }

    /**
     * 写入一个分片。
     *
     * <p>按 {@code 类型 + 分隔符 + 数据} 的顺序拼装字节后写入连接，
     * 写入失败会关闭并回收连接，避免半死连接继续占用资源。</p>
     *
     * @param streamType 分片类型。
     * @param data       分片数据。
     */
    public void write(StreamType streamType, JSONObject data) {
        // 数据格式：TYPE+DATA(Sink)
        byte[] type = streamType.name.getBytes(StandardCharsets.UTF_8);
        byte[] sink = data.toString().getBytes(StandardCharsets.UTF_8);

        byte[] buf = new byte[type.length + StreamServer.sSeparator.length + sink.length];
        System.arraycopy(type, 0, buf, 0, type.length);
        System.arraycopy(StreamServer.sSeparator, 0, buf, type.length, StreamServer.sSeparator.length);
        System.arraycopy(sink, 0, buf, type.length + StreamServer.sSeparator.length, sink.length);

        Message message = new Message(buf);
        // 禁用压缩
        message.disableCompression();
        try {
            this.acceptor.write(this.session, message);
        } catch (IOException e) {
            Logger.e(this.getClass(), "#write", e);
        }
    }
}
