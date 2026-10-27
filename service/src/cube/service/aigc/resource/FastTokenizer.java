/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.resource;

import cube.aigc.Tokenizable;
import cube.util.tokenizer.Tokenizer;
import cube.util.tokenizer.keyword.TFIDFAnalyzer;

import java.util.List;

/**
 * 分词器适配。
 *
 * <p>把底层分词组件包装为统一接口，供检索与关键词抽取共用。</p>
 */
public class FastTokenizer implements Tokenizable {

    private final Tokenizer tokenizer;

    public FastTokenizer(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * 分词。
     *
     * @param text 待分词文本。
     * @return 词序列；文本为空时返回空列表。
     */
    @Override
    public List<String> segment(String text) {
        return this.tokenizer.sentenceProcess(text);
    }

    /**
     * 抽取关键词。
     *
     * <p>基于 TF-IDF 计算权重，按权重降序取前 N 个。</p>
     *
     * @param text 待分析文本。
     * @param topN 返回的关键词数量上限。
     * @return 关键词列表；文本为空时返回空列表。
     */
    @Override
    public List<String> analyze(String text, int topN) {
        TFIDFAnalyzer analyzer = new TFIDFAnalyzer(this.tokenizer);
        return analyzer.analyzeOnlyWords(text, topN);
    }
}
