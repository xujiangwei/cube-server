/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.resource;

import cube.common.entity.SearchResult;
import cube.service.aigc.AIGCService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

/**
 * 资源搜索器。
 */
public abstract class ResourceSearcher {

    /** AIGC 服务实例，供检索实现调用存储与模型能力。 */
    protected final AIGCService service;

    public ResourceSearcher(AIGCService service) {
        this.service = service;
    }

    /**
     * 执行搜索。
     * @param words 词列表。
     * @return 返回是否执行成功。
     */
    public abstract boolean search(List<String> words);

    /**
     * 填充搜索结果。
     * @param searchResult searchResult。
     */
    public abstract void fillSearchResult(SearchResult searchResult);

    /**
     * 创建搜索链接。
     * @param words 词列表。
     * @return 处理结果。
     */
    protected abstract String makeURL(List<String> words);
}
