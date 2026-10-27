/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.plugin;

/**
 * 插件进行数据传递的上下文。
 */
public abstract class PluginContext {

    private String key;

    /** 调用方传入的参数。 */
    protected Object parameter;

    public PluginContext() {
    }

    /**
     * 设置上下文键。
     *
     * @param key 键名。
     */
    protected void setKey(String key) {
        this.key = key;
    }

    /**
     * 获取当前上下文的 Hook 键。
     *
     * @return 返回当前上下文的键。
     */
    public String getKey() {
        return this.key;
    }

    /**
     * 判断是否携带参数。
     *
     * @return 携带参数返回 {@code true} 。
     */
    public boolean hasParameter() {
        return (null != this.parameter);
    }

    /**
     * 设置参数。
     *
     * @param parameter 参数对象。
     */
    public void setParameter(Object parameter) {
        this.parameter = parameter;
    }

    /**
     * 获取参数。
     *
     * @return 参数对象；未设置返回 {@code null} 。
     */
    public Object getParameter() {
        return this.parameter;
    }

    /**
     * 按名称读取上下文属性。
     *
     * @param name 属性名。
     * @return 属性值；未知名称返回 {@code null} 。
     */
    public abstract Object get(String name);

    /**
     * 按名称写入上下文属性。
     *
     * @param name  属性名。
     * @param value 属性值。
     */
    public abstract void set(String name, Object value);
}
