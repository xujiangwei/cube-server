/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc;

import cube.plugin.PluginSystem;

/**
 * AIGC 插件系统。
 */
public class AIGCPluginSystem extends PluginSystem<AIGCHook> {

    public AIGCPluginSystem() {
        this.build();
    }

    /**
     * 获取应用事件钩子。
     *
     * @return 钩子实例。
     */
    public AIGCHook getAppEventHook() {
        return this.getHook(AIGCHook.AppEvent);
    }

    /**
     * 获取导入知识文档钩子。
     *
     * @return 钩子实例。
     */
    public AIGCHook getImportKnowledgeDocHook() {
        return this.getHook(AIGCHook.ImportKnowledgeDoc);
    }

    /**
     * 获取移除知识文档钩子。
     *
     * @return 钩子实例。
     */
    public AIGCHook getRemoveKnowledgeDocHook() {
        return this.getHook(AIGCHook.RemoveKnowledgeDoc);
    }

    /**
     * 获取任务处理钩子。
     *
     * @return 钩子实例。
     */
    public AIGCHook getTaskProcessingHook() {
        return this.getHook(AIGCHook.TaskProcessing);
    }

    private void build() {
        AIGCHook hook = new AIGCHook(AIGCHook.AppEvent);
        this.addHook(hook);

        hook = new AIGCHook(AIGCHook.ImportKnowledgeDoc);
        this.addHook(hook);

        hook = new AIGCHook(AIGCHook.RemoveKnowledgeDoc);
        this.addHook(hook);

        hook = new AIGCHook(AIGCHook.TaskProcessing);
        this.addHook(hook);
    }
}
