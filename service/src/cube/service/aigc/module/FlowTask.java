/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.module;

import cube.aigc.Flowable;
import cube.service.aigc.AIGCService;

/**
 * 流程任务抽象基类。
 *
 * <p>持有服务实例与原始提问，子类实现具体的处理步骤；
 * 任务本身可被编排器串联执行。</p>
 */
public abstract class FlowTask implements Flowable {

    /** AIGC 服务实例，供子类调用模型与存储能力。 */
    protected AIGCService service;

    /** 触发本任务的用户提问。 */
    protected String query;

    public FlowTask(AIGCService service, String query) {
        this.service = service;
        this.query = query;
    }
}
