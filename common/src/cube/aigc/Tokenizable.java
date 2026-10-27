/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc;

import java.util.List;

public interface Tokenizable {

    /**
     * 执行分词。
     *
     * @param text 待分词的文本。
     * @return 词序列；文本为空时返回空列表。
     */
    List<String> segment(String text);

    /**
     * 分析关键词。
     *
     * @param text 待分析的文本。
     * @param topN 返回数量上限。
     * @return 关键词列表；文本为空时返回空列表。
     */
    List<String> analyze(String text, int topN);
}
