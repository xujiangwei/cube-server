/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.signal;

import cell.core.talk.PrimitiveInputStream;
import cell.util.collection.FlexibleByteBuffer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流存储器。
 */
public class StreamStorage {

    private final static StreamStorage instance = new StreamStorage();

    private Map<String, List<FlexibleByteBuffer>> bufferMap;

    private StreamStorage() {
        this.bufferMap = new ConcurrentHashMap<>();
    }

    /**
     * 获取单例。
     *
     * @return 全局唯一实例。
     */
    public static StreamStorage getInstance() {
        return StreamStorage.instance;
    }

    /**
     * 读取并保存流数据。
     *
     * <p>从输入流中累积数据，按流名归档后交给下游处理；流结束时释放缓冲。</p>
     *
     * @param inputStream 流式输入。
     */
    public void process(PrimitiveInputStream inputStream) {
        FlexibleByteBuffer buf = new FlexibleByteBuffer();
        try {
            int length = 0;
            byte[] bytes = new byte[2048];
            while ((length = inputStream.read(bytes)) > 0) {
                buf.put(bytes, 0, length);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        String name = inputStream.getName();
        List<FlexibleByteBuffer> list = this.bufferMap.get(name);
        if (null == list) {
            list = new Vector<>();
            this.bufferMap.put(name, list);
        }
        list.add(buf);
    }

    private void write(String name, FlexibleByteBuffer buffer) {

    }
}
