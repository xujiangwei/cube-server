/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.plugin;

import cell.util.log.Logger;
import cube.auth.AuthToken;
import cube.plugin.HookResult;
import cube.plugin.Plugin;
import cube.plugin.PluginContext;
import cube.service.aigc.AIGCService;
import cube.service.auth.AuthPluginContext;

/**
 * 注入令牌时处理插件。
 */
public class InjectTokenPlugin implements Plugin {

    private final AIGCService service;

    public InjectTokenPlugin(AIGCService service) {
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
     * 注入访问令牌。
     *
     * <p>为上下文中的联系人换取令牌并回填；异常只记录日志。</p>
     *
     * @param context 插件上下文。
     * @return 固定返回 {@code null} 。
     */
    @Override
    public HookResult launch(PluginContext context) {
        try {
            AuthPluginContext ctx = (AuthPluginContext) context;
            AuthToken token = ctx.getToken();
            this.service.newInvitationForToken(token.getCode());
            Logger.i(this.getClass(), "Insert invitation for token \"" + token.getCode() + "\"");
        } catch (Exception e) {
            Logger.e(this.getClass(), "#launch", e);
        }

        return null;
    }
}
