/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.plugin;

import cell.util.log.Logger;
import cube.aigc.AppEvent;
import cube.common.entity.Contact;
import cube.common.entity.FileLabel;
import cube.plugin.HookResult;
import cube.plugin.Plugin;
import cube.plugin.PluginContext;
import cube.service.aigc.AIGCService;

/**
 * 保存文件。
 */
public class NewFilePlugin implements Plugin {

    private final AIGCService service;

    public NewFilePlugin(AIGCService service) {
        this.service = service;
    }

    /**
     * 初始化插件。
     *
     * <p>当前无需初始化。</p>
     */
    @Override
    public void setup() {
        // Nothing
    }

    /**
     * 释放插件。
     *
     * <p>当前无需清理。</p>
     */
    @Override
    public void teardown() {
        // Nothing
    }

    /**
     * 处理新文件事件。
     *
     * <p>为新提交的文件建立索引或触发后处理；异常只记录日志。</p>
     *
     * @param context 插件上下文。
     * @return 固定返回 {@code null} 。
     */
    @Override
    public HookResult launch(PluginContext context) {
        try {
            FileLabel fileLabel = (FileLabel) context.get("fileLabel");
            Contact contact = (Contact) context.get("contact");

            AppEvent appEvent = new AppEvent(AppEvent.NewFile, System.currentTimeMillis(),
                    contact.getId(), fileLabel.toCompactJSON());
            this.service.getStorage().writeAppEvent(appEvent);
        } catch (Exception e) {
            Logger.w(this.getClass(), "#launch", e);
        }

        return null;
    }
}
