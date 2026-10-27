/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 知识库文档范围。
 */
public enum KnowledgeScope {

    /**
     * 全局。
     */
    Global("global"),

    /**
     * 公共。
     */
    Public("public"),

    /**
     * 私有。
     */
    Private("private")

    ;

    /** 名称。 */
    public final String name;

    KnowledgeScope(String name) {
        this.name = name;
    }

    /**
     * 按编码解析。
     *
     * <p>遍历全部枚举值逐个匹配，未命中时返回 {@code null} 。</p>
     *
     * @param name 编码值。
     * @return 对应枚举；未命中返回 {@code null} 。
     */
    public static KnowledgeScope parse(String name) {
        for (KnowledgeScope scope : KnowledgeScope.values()) {
            if (scope.name.equalsIgnoreCase(name)) {
                return scope;
            }
        }
        return KnowledgeScope.Private;
    }
}
