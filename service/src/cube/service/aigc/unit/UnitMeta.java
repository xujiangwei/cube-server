/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.unit;

import cube.common.entity.AIGCUnit;
import cube.common.entity.FileLabel;
import cube.common.entity.RetrieveReRankResult;
import cube.service.aigc.AIGCService;
import cube.util.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 单元任务抽象基类。
 *
 * <p>持有服务实例与目标单元，子类实现 {@link #process()} 完成具体处理。
 * 任务对象由执行器创建、调度并回收。</p>
 */
public abstract class UnitMeta {

    /** AIGC 服务实例。 */
    protected final AIGCService service;

    /** 执行本任务的单元。 */
    public final AIGCUnit unit;

    public UnitMeta(AIGCService service, AIGCUnit unit) {
        this.service = service;
        this.unit = unit;
    }

    /**
     * 分析附件并返回重排后的检索结果。
     *
     * <p>对每个文件做检索与重排，用于把附件内容带入上下文。</p>
     *
     * @param fileLabels 附件列表。
     * @param query      用户提问。
     * @return 重排结果列表；无可用附件时为空列表。
     */
    protected List<RetrieveReRankResult> analyseFiles(List<FileLabel> fileLabels, String query) {
        List<RetrieveReRankResult> result = new ArrayList<>();
        if (fileLabels.isEmpty()) {
            return result;
        }

        return this.service.syncRetrieveReRank(fileLabels, query);
    }

    /**
     * 计算文本的 Token 列表。
     * @param text 待处理文本。
     * @return 字符串列表。
     */
    public List<String> calcTokens(String text) {
        List<String> tokens = this.service.getTokenizer().sentenceProcess(text);
        tokens.removeIf(s -> !TextUtils.isChineseWord(s) && !TextUtils.isWord(s));
        return tokens;
    }

    /**
     * 执行任务。
     *
     * <p>子类在此完成实际的模型调用与结果回写。</p>
     */
    public abstract void process();
}
