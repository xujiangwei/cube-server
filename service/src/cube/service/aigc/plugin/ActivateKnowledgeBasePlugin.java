/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.plugin;

import cell.util.log.Logger;
import cube.auth.AuthConsts;
import cube.common.entity.Contact;
import cube.common.entity.KnowledgeProfile;
import cube.common.entity.KnowledgeScope;
import cube.plugin.HookResult;
import cube.plugin.Plugin;
import cube.plugin.PluginContext;
import cube.service.aigc.AIGCService;
import cube.service.contact.ContactPluginContext;

/**
 * 当创建新联系人时激活知识库。
 */
public class ActivateKnowledgeBasePlugin implements Plugin {

    private final AIGCService service;

    public ActivateKnowledgeBasePlugin(AIGCService service) {
        this.service = service;
    }

    /**
     * 初始化插件。
     *
     * <p>当前无需初始化：插件只持有服务引用，不注册额外资源。</p>
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
     * 为新联系人开通知识库。
     *
     * <p>把联系人的知识库侧写置为正常状态并设置默认容量；
     * 异常只记录日志，不影响调用方主流程。</p>
     *
     * @param context 插件上下文，需为 {@code ContactPluginContext} 。
     * @return 固定返回 {@code null} ，表示不需要中断后续处理。
     */
    @Override
    public HookResult launch(PluginContext context) {
        try {
            ContactPluginContext ctx = (ContactPluginContext) context;
            Contact contact = ctx.getContact();

            Logger.i(ActivateKnowledgeBasePlugin.class, "Activate knowledge base: " + contact.getId());

            // 更新
            this.service.getStorage().updateKnowledgeProfile(contact.getId(), AuthConsts.DEFAULT_DOMAIN,
                    KnowledgeProfile.STATE_NORMAL, 549755813888L, KnowledgeScope.Private);
        } catch (Exception e) {
            Logger.e(this.getClass(), "#launch", e);
        }

        return null;
    }
}
