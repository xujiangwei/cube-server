/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.stream;

/**
 * 流分片监听器。
 *
 * <p>由流服务器在切分出一个完整分片后回调，实现方负责按分片类型处理数据。
 * 回调运行在 IO 线程上，耗时处理应转交其他线程，否则会阻塞后续分片。</p>
 */
public interface StreamListener {

    void onStream(Track track, Stream stream);
}
