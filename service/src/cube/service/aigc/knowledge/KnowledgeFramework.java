/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.knowledge;

import cell.util.log.Logger;
import cube.aigc.AppEvent;
import cube.auth.AuthToken;
import cube.common.entity.KnowledgeBaseInfo;
import cube.common.entity.KnowledgeProfile;
import cube.common.entity.KnowledgeScope;
import cube.core.AbstractModule;
import cube.service.aigc.AIGCService;
import cube.service.auth.AuthService;
import cube.util.TextUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 知识库框架。
 */
public class KnowledgeFramework {

    /** 默认知识库的内部名称。 */
    public final static String DefaultName = "document";

    /** 默认知识库的展示名称。 */
    public final static String DefaultDisplayName = "默认文档库";

    private AIGCService service;

    private AuthService authService;

    private AbstractModule fileStorage;

    /**
     * Key: 联系人 ID 。
     */
    private Map<Long, FrameworkWrapper> frameworks;

    public KnowledgeFramework(AIGCService service, AuthService authService, AbstractModule fileStorage) {
        this.service = service;
        this.authService = authService;
        this.fileStorage = fileStorage;
        this.frameworks = new HashMap<>();
    }

    /**
     * 周期维护。
     *
     * <p>清理长时间未访问的框架缓存，避免按联系人累积占用内存。</p>
     *
     * @param now 当前时间戳，单位毫秒。
     */
    public void onTick(long now) {
        synchronized (this) {
            Iterator<FrameworkWrapper> iter = this.frameworks.values().iterator();
            while (iter.hasNext()) {
                List<KnowledgeBase> baseList = iter.next().getAllKnowledgeBases();
                for (KnowledgeBase base : baseList) {
                    base.onTick(now);
                }
            }
        }
    }

    /**
     * 获取联系人的全部知识库概要。
     *
     * @param contactId 联系人 ID 。
     * @return 概要列表；无知识库时为空列表。
     */
    public List<KnowledgeBaseInfo> getKnowledgeBaseInfos(long contactId) {
        FrameworkWrapper framework = null;

        synchronized (this) {
            framework = this.frameworks.get(contactId);
            if (null == framework) {
                framework = new FrameworkWrapper(contactId, this.service);
                this.frameworks.put(contactId, framework);
            }
        }

        return framework.getBaseInfos();
    }

    /**
     * 获取指定知识库。
     *
     * @param contactId 联系人 ID 。
     * @param baseName  知识库名称。
     * @return 知识库；不存在返回 {@code null} 。
     */
    public KnowledgeBase getKnowledgeBase(long contactId, String baseName) {
        FrameworkWrapper framework = null;

        synchronized (this) {
            framework = this.frameworks.get(contactId);
            if (null == framework) {
                framework = new FrameworkWrapper(contactId, this.service);
                this.frameworks.put(contactId, framework);
            }

            KnowledgeBase base = framework.getKnowledgeBase(baseName);
            if (null == base) {
                KnowledgeBaseInfo info = framework.getKnowledgeBaseInfo(baseName);
                if (null != info) {
                    AuthToken authToken = this.authService.queryAuthTokenByContactId(contactId);
                    base = new KnowledgeBase(info,
                            this.service, this.service.getStorage(), authToken, this.fileStorage);
                    framework.putKnowledgeBase(base);
                    base.listKnowledgeDocs();
                    base.listKnowledgeArticles();
                }
            }

            // 判断是否是默认库
            if (null == base && KnowledgeFramework.DefaultName.equals(baseName)) {
                // 创建默认库
                AuthToken authToken = this.authService.queryAuthTokenByContactId(contactId);
                // 新库
                KnowledgeBaseInfo info = this.newKnowledgeBase(authToken.getCode(), KnowledgeFramework.DefaultName,
                        KnowledgeFramework.DefaultDisplayName, KnowledgeFramework.DefaultDisplayName,
                        KnowledgeScope.Private);
                if (null != info) {
                    base = new KnowledgeBase(info,
                            this.service, this.service.getStorage(), authToken, this.fileStorage);
                    framework.putKnowledgeBase(base);
                    base.listKnowledgeDocs();
                    base.listKnowledgeArticles();
                }
            }

            return base;
        }
    }

    /**
     * 按分类获取联系人的知识库。
     *
     * @param contactId 联系人 ID 。
     * @param category  分类。
     * @return 知识库列表；无匹配时为空列表。
     */
    public List<KnowledgeBase> getKnowledgeBaseByCategory(long contactId, String category) {
        FrameworkWrapper framework = null;
        synchronized (this) {
            framework = this.frameworks.get(contactId);
            if (null == framework) {
                framework = new FrameworkWrapper(contactId, this.service);
                this.frameworks.put(contactId, framework);
            }

            List<KnowledgeBase> baseList = framework.getKnowledgeBaseByCategory(category);
            if (baseList.isEmpty()) {
                List<KnowledgeBaseInfo> infoList = framework.getKnowledgeBaseInfoByCategory(category);
                AuthToken authToken = this.authService.queryAuthTokenByContactId(contactId);

                for (KnowledgeBaseInfo info : infoList) {
                    KnowledgeBase base = new KnowledgeBase(info,
                            this.service, this.service.getStorage(), authToken, this.fileStorage);
                    framework.putKnowledgeBase(base);
                    base.listKnowledgeDocs();
                    base.listKnowledgeArticles();
                    baseList.add(base);
                }
            }
            return baseList;
        }
    }

    /**
     * 新建知识库。
     *
     * <p>名称需在域内唯一；创建后同时登记到框架并写入存储。</p>
     *
     * @param token       访问令牌。
     * @param name        知识库内部名称。
     * @param displayName 展示名称。
     * @param category    分类。
     * @param scope       可见范围。
     * @return 新建的知识库概要；失败返回 {@code null} 。
     */
    public KnowledgeBaseInfo newKnowledgeBase(String token, String name, String displayName,
                                              String category, KnowledgeScope scope) {
        if (TextUtils.isChineseWord(name)) {
            Logger.w(this.getClass(), "#newKnowledgeBase - Knowledge base name is chinese: " + name);
            return null;
        }

        AuthToken authToken = this.service.getToken(token);
        if (null == authToken) {
            Logger.w(this.getClass(), "#newKnowledgeBase - " + name + " - Token error: " + token);
            return null;
        }

        long contactId = authToken.getContactId();

        KnowledgeBaseInfo info = this.service.getStorage().readKnowledgeBaseInfo(contactId, name);
        if (null != info) {
            Logger.w(this.getClass(), "#newKnowledgeBase - Knowledge base already exist: " + name);
            return null;
        }

        info = new KnowledgeBaseInfo(contactId, name, displayName, category, 0, 0, System.currentTimeMillis());
        // 写入信息
        if (this.service.getStorage().writeKnowledgeBaseInfo(info)) {
            // 获取实例
            KnowledgeBase base = this.getKnowledgeBase(contactId, name);
            if (null != base) {
                base.updateProfile(KnowledgeProfile.STATE_NORMAL, 0, scope);

                // 记录事件
                AppEvent appEvent = new AppEvent(AppEvent.NewKnowledgeBase, System.currentTimeMillis(),
                        authToken.getContactId(),
                        base.toJSON());
                service.getStorage().writeAppEvent(appEvent);

                return info;
            }
            else {
                Logger.e(this.getClass(), "#newKnowledgeBase - New knowledge base failed: " + name);
            }
        }

        return null;
    }

    /**
     * 按令牌删除知识库。
     *
     * <p>默认知识库不允许删除。</p>
     *
     * @param token 访问令牌。
     * @param name  知识库名称。
     * @return 被删除的概要；不允许删除或失败时返回 {@code null} 。
     */
    public KnowledgeBaseInfo deleteKnowledgeBase(String token, String name) {
        AuthToken authToken = this.service.getToken(token);
        if (null == authToken) {
            Logger.w(this.getClass(), "#deleteKnowledgeBase - " + name + " - Token error: " + token);
            return null;
        }

        return this.deleteKnowledgeBase(authToken.getContactId(), name);
    }

    /**
     * 按联系人删除知识库。
     *
     * <p>默认知识库不允许删除。</p>
     *
     * @param contactId 联系人 ID 。
     * @param name      知识库名称。
     * @return 被删除的概要；不允许删除或失败时返回 {@code null} 。
     */
    public KnowledgeBaseInfo deleteKnowledgeBase(long contactId, String name) {
        if (DefaultName.equals(name)) {
            Logger.w(this.getClass(), "#deleteKnowledgeBase - Default knowledge base can NOT delete: " + name);
            return null;
        }

        KnowledgeBase base = this.getKnowledgeBase(contactId, name);
        if (null == base) {
            Logger.w(this.getClass(), "#deleteKnowledgeBase - Knowledge base is null: " + name);
            return null;
        }

        // 删除库的所有数据
        base.destroy();

        synchronized (this) {
            KnowledgeBaseInfo info = null;
            FrameworkWrapper framework = this.frameworks.get(contactId);
            if (null != framework) {
                info = framework.getKnowledgeBaseInfo(name);
                framework.removeKnowledgeBase(name);
            }

            this.service.getStorage().deleteKnowledgeBaseInfo(contactId, name);

            return info;
        }
    }

    /**
     * 更新知识库信息。
     *
     * <p>默认知识库的名称与分类不允许修改，只同步其余字段。</p>
     *
     * @param token 访问令牌。
     * @param info  知识库概要。
     * @return 更新后的概要；失败返回 {@code null} 。
     */
    public KnowledgeBaseInfo updateKnowledgeBase(String token, KnowledgeBaseInfo info) {
        if (DefaultName.equals(info.name)) {
            Logger.w(this.getClass(), "#updateKnowledgeBase - Default knowledge base can NOT update: " + info.name);
            return null;
        }

        AuthToken authToken = this.service.getToken(token);
        if (null == authToken) {
            Logger.w(this.getClass(), "#updateKnowledgeBase - Token error: " + token);
            return null;
        }

        long contactId = authToken.getContactId();

        if (this.service.getStorage().updateKnowledgeBaseInfo(contactId, info)) {
            KnowledgeBase base = this.getKnowledgeBase(contactId, info.name);
            if (null != base) {
                synchronized (this) {
                    FrameworkWrapper framework = this.frameworks.get(contactId);
                    if (null != framework) {
                        framework.refreshKnowledgeBaseInfo();
                        return framework.getKnowledgeBaseInfo(info.name);
                    }
                }
            }
        }

        return null;
    }

    /**
     * 释放联系人名下的知识库缓存。
     *
     * <p>只清理内存中的框架与实例，不删除存储数据。</p>
     *
     * @param domain    域。
     * @param contactId 联系人 ID 。
     */
    public void freeBase(String domain, Long contactId) {
        synchronized (this) {
            FrameworkWrapper framework = this.frameworks.get(contactId);
            if (null == framework) {
                return;
            }

            this.frameworks.remove(contactId);
            framework.destroy();
        }
    }
}
