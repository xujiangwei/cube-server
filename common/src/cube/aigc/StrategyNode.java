/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cube.common.JSONable;
import cube.common.Language;
import cube.common.entity.GeneratingRecord;
import org.json.JSONObject;

/**
 * 策略节点。
 */
public abstract class StrategyNode implements JSONable {

    /** 执行本节点的单元名。 */
    protected final String unitName;

    /** 输出语言。 */
    protected final Language language;

    /** 本节点的生成结果，供下游节点读取。 */
    protected GeneratingRecord result;

    /** 下一个节点。 */
    protected StrategyNode next;

    public StrategyNode(String unitName, Language language) {
        this.unitName = unitName;
        this.language = language;
    }

    /**
     * 获取执行单元名。
     *
     * @return 单元名。
     */
    public String getUnitName() {
        return this.unitName;
    }

    /**
     * 获取输出语言。
     *
     * @return 语言。
     */
    public Language getLanguage() {
        return this.language;
    }

    /**
     * 获取本节点的生成结果。
     *
     * @return 生成结果；尚未执行返回 {@code null} 。
     */
    public GeneratingRecord getResult() {
        return this.result;
    }

    /**
     * 连接下一个节点。
     *
     * @param next 下一个节点。
     * @return 当前节点，便于链式调用。
     */
    public StrategyNode link(StrategyNode next) {
        this.next = next;
        return next;
    }

    /**
     * 获取下一个节点。
     *
     * @return 下一个节点；已是末节点返回 {@code null} 。
     */
    public StrategyNode next() {
        return this.next;
    }

    /**
     * 执行本节点。
     *
     * <p>子类在此调用具体能力并返回可供下游使用的文本。</p>
     *
     * @param input 上游节点的生成结果。
     * @return 本节点的输出文本。
     */
    public abstract String perform(GeneratingRecord input);

    /**
     * 只保留文本的首行。
     *
     * <p>用于丢弃模型输出中的多余解释，只取第一行结论。</p>
     *
     * @param text 原始文本。
     * @return 首行文本。
     */
    protected String filterFirstLine(String text) {
        int index = text.indexOf("\n");
        if (index > 0) {
            String result = text.substring(index);
            while (result.startsWith("\n")) {
                result = result.substring(1);
            }
            return result;
        }
        else {
            return text;
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
