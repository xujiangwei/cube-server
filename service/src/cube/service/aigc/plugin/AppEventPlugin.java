/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.plugin;

import cube.aigc.AppEvent;
import cube.plugin.HookResult;
import cube.plugin.Plugin;
import cube.plugin.PluginContext;
import cube.service.aigc.AIGCPluginContext;
import cube.service.aigc.AIGCService;

/**
 * 应用事件插件。
 *
 * <p>在应用事件到达时被唤起，用于把事件接入后续处理链路。</p>
 */
public class AppEventPlugin implements Plugin {

    private final AIGCService service;

    public AppEventPlugin(AIGCService service) {
        this.service = service;
    }

    /**
     * 初始化插件。
     *
     * <p>当前无需初始化。</p>
     */
    @Override
    public void setup() {

    }

    /**
     * 释放插件。
     *
     * <p>当前无需清理。</p>
     */
    @Override
    public void teardown() {

    }

    /**
     * 处理应用事件。
     *
     * <p>从上下文取出应用事件；当前未做进一步处理，直接返回 {@code null} 。</p>
     *
     * @param context 插件上下文，需为 {@code AIGCPluginContext} 。
     * @return 固定返回 {@code null} 。
     */
    @Override
    public HookResult launch(PluginContext context) {
        AppEvent event = ((AIGCPluginContext) context).getAppEvent();
        return null;
    }
}
