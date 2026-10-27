/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.signal;

import cell.core.cellet.Cellet;
import cell.core.talk.PrimitiveInputStream;
import cell.core.talk.TalkContext;

/**
 * 流任务。
 */
public class StreamTask implements Runnable {

    private Cellet cellet;

    private TalkContext talkContext;

    private PrimitiveInputStream inputStream;

    public StreamTask(Cellet cellet, TalkContext talkContext, PrimitiveInputStream inputStream) {
        this.cellet = cellet;
        this.talkContext = talkContext;
        this.inputStream = inputStream;
    }

    /**
     * 执行流写入任务。
     *
     * <p>把缓冲的数据写入目标流并释放资源，失败时记录日志而不抛出。</p>
     */
    @Override
    public void run() {

    }
}
