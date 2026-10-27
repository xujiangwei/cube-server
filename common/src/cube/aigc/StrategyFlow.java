/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cube.common.entity.GeneratingOption;
import cube.common.entity.GeneratingRecord;

/**
 * 策略流程。
 *
 * <p>把若干 {@link StrategyNode} 串成执行链，依次调用直到链条结束或中途失败。</p>
 */
public class StrategyFlow implements Flowable {

    private StrategyNode entry;

    public StrategyFlow(StrategyNode entry) {
        this.entry = entry;
    }

    /**
     * 驱动整条策略链执行。
     *
     * @param generator 生成器实例。
     * @return 末节点的生成结果；链条为空返回 {@code null} 。
     */
    @Override
    public GeneratingRecord generate(Generatable generator) {
        StrategyNode node = this.entry;
        GeneratingRecord result = null;
        while (null != node) {
            // 执行生成提示词
            String prompt = node.perform(result);
            if (null == prompt) {
                result = null;
                break;
            }

            // 生成数据
            result = generator.generateText(node.getUnitName(), prompt, new GeneratingOption(), null);
            if (null == result) {
                break;
            }

            node = node.next();
        }

        return result;
    }
}
