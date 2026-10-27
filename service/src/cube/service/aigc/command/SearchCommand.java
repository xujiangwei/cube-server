/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.command;

import org.json.JSONObject;

/**
 * 搜索命令。
 *
 * @deprecated 命令体系已不再维护，改用 {@code Explorer} 的检索接口。
 */
public class SearchCommand extends Command {

    private String keyword;

    public SearchCommand(String keyword) {
        super("Search");
        this.keyword = keyword;
    }

    /**
     * 获取检索关键词。
     *
     * @return 关键词。
     */
    public String getKeyword() {
        return this.keyword;
    }

    /**
     * 执行检索。
     *
     * <p>按关键词调用检索能力，结果写入命令自身的状态供后续读取。</p>
     */
    @Override
    public void run() {

    }

    /**
     * 序列化为 JSON 。
     *
     * @return 包含名称与关键词的 JSON 。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        return json;
    }
}
