/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.knowledge;

import cube.common.entity.KnowledgeBaseInfo;
import cube.service.aigc.AIGCService;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库框架包装。
 *
 * <p>持有某个联系人名下的知识库集合与概要列表，提供按名称、分类检索与增删。
 * 框架在访问时惰性构建，构建完成后由定时任务刷新概要。</p>
 */
public class FrameworkWrapper {

    private final long contactId;

    private final AIGCService service;

    private final Vector<KnowledgeBaseInfo> baseInfoList;

    /**
     * Key: 知识库名。
     */
    private ConcurrentHashMap<String, KnowledgeBase> knowledgeMap;

    public FrameworkWrapper(long contactId, AIGCService service) {
        this.contactId = contactId;
        this.service = service;
        this.baseInfoList = new Vector<>();
        this.knowledgeMap = new ConcurrentHashMap<>();
        this.refreshKnowledgeBaseInfo();
    }

    /**
     * 获取全部知识库概要。
     *
     * <p>返回前会重建概要列表，保证与当前知识库集合一致。</p>
     *
     * @return 概要列表；无知识库时为空列表。
     */
    public List<KnowledgeBaseInfo> getBaseInfos() {
        this.baseInfoList.clear();
        List<KnowledgeBaseInfo> list = this.service.getStorage().readKnowledgeBaseInfo(this.contactId);
        this.baseInfoList.addAll(list);
        return new ArrayList<>(this.baseInfoList);
    }

    /**
     * 获取全部知识库。
     *
     * @return 知识库列表；无知识库时为空列表。
     */
    public List<KnowledgeBase> getAllKnowledgeBases() {
        List<KnowledgeBase> list = new ArrayList<>();
        list.addAll(this.knowledgeMap.values());
        return list;
    }

    /**
     * 按名称获取知识库。
     *
     * @param name 知识库名称。
     * @return 知识库；不存在返回 {@code null} 。
     */
    public KnowledgeBase getKnowledgeBase(String name) {
        return this.knowledgeMap.get(name);
    }

    /**
     * 按分类获取知识库。
     *
     * @param category 分类。
     * @return 匹配的知识库列表；无匹配时为空列表。
     */
    public List<KnowledgeBase> getKnowledgeBaseByCategory(String category) {
        List<KnowledgeBase> list = new ArrayList<>();

        synchronized (this.baseInfoList) {
            for (KnowledgeBaseInfo info : this.baseInfoList) {
                if (null != info.category && info.category.equals(category)) {
                    KnowledgeBase base = this.getKnowledgeBase(info.name);
                    if (null != base) {
                        list.add(base);
                    }
                }
            }
        }

        return list;
    }

    /**
     * 放入知识库。
     *
     * <p>同名知识库会被覆盖，同时刷新概要列表。</p>
     *
     * @param knowledgeBase 知识库。
     */
    public void putKnowledgeBase(KnowledgeBase knowledgeBase) {
        this.knowledgeMap.put(knowledgeBase.getName(), knowledgeBase);
    }

    /**
     * 移除知识库。
     *
     * @param name 知识库名称。
     * @return 被移除的知识库；不存在返回 {@code null} 。
     */
    public KnowledgeBase removeKnowledgeBase(String name) {
        KnowledgeBase base = this.knowledgeMap.remove(name);
        synchronized (this.baseInfoList) {
            this.baseInfoList.removeIf(info -> info.name.equals(name));
        }
        return base;
    }

    /**
     * 获取知识库概要。
     *
     * @param baseName 知识库名称。
     * @return 概要；不存在返回 {@code null} 。
     */
    public KnowledgeBaseInfo getKnowledgeBaseInfo(String baseName) {
        synchronized (this.baseInfoList) {
            for (KnowledgeBaseInfo info : this.baseInfoList) {
                if (info.name.equals(baseName)) {
                    return info;
                }
            }
        }

        // 查询已创建的库
        KnowledgeBaseInfo info = this.service.getStorage().readKnowledgeBaseInfo(this.contactId, baseName);
        if (null != info) {
            this.baseInfoList.add(info);
        }

        return info;
    }

    /**
     * 按分类获取知识库概要。
     *
     * @param category 分类。
     * @return 概要列表；无匹配时为空列表。
     */
    public List<KnowledgeBaseInfo> getKnowledgeBaseInfoByCategory(String category) {
        List<KnowledgeBaseInfo> infos = new ArrayList<>();

        synchronized (this.baseInfoList) {
            for (KnowledgeBaseInfo info : this.baseInfoList) {
                if (null != info.category && info.category.equals(category)) {
                    infos.add(info);
                }
            }
        }
        if (!infos.isEmpty()) {
            return infos;
        }

        infos = this.service.getStorage().readKnowledgeBaseInfoByCategory(this.contactId, category);
        for (KnowledgeBaseInfo info : infos) {
            if (!this.baseInfoList.contains(info)) {
                this.baseInfoList.add(info);
            }
        }

        return infos;
    }

    /**
     * 刷新知识库概要。
     *
     * <p>重建概要列表，使文档数量、容量等统计与最新状态一致。</p>
     */
    public void refreshKnowledgeBaseInfo() {
        synchronized (this.baseInfoList) {
            this.baseInfoList.clear();

            List<KnowledgeBaseInfo> list = this.service.getStorage().readKnowledgeBaseInfo(this.contactId);
            if (list.isEmpty()) {
                // 写入默认库
                KnowledgeBaseInfo info = new KnowledgeBaseInfo(this.contactId, KnowledgeFramework.DefaultName,
                        KnowledgeFramework.DefaultDisplayName, null, 0, 0, System.currentTimeMillis());
                service.getStorage().writeKnowledgeBaseInfo(info);
                this.baseInfoList.add(info);
            }
            else {
                this.baseInfoList.addAll(list);
            }
        }
    }

    /**
     * 销毁包装。
     *
     * <p>清空概要列表与知识库映射，释放对底层存储的引用。</p>
     */
    public void destroy() {
        this.baseInfoList.clear();
        this.knowledgeMap.clear();
    }
}
