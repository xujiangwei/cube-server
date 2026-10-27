/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 用于异步操作时进行上下文操作的群组。
 */
public class MutableGroup {

    private Group group = null;

    public MutableGroup() {
    }

    /**
     * 替换内部群组。
     *
     * @param group 新的群组对象。
     */
    public void set(Group group) {
        this.group = group;
    }

    /**
     * 获取内部群组。
     *
     * @return 群组对象。
     */
    public Group get() {
        return this.group;
    }

    /**
     * 判断是否空。
     *
     * @return 空。
     */
    public boolean isNull() {
        return (null == this.group);
    }
}
