/*
 * This file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

public class IPEntry {

    private final String ip;
    private String mainInfo = "";
    private String subInfo = "";

    public IPEntry(final String ip) {
        this.ip = ip;
    }

    /**
     * 获取 IP 地址。
     *
     * @return IP 地址。
     */
    public String getIp() {
        return ip;
    }

    /**
     * 获取主信息。
     *
     * @return 主信息。
     */
    public String getMainInfo() {
        return mainInfo;
    }

    /**
     * 获取归属地等附加信息。
     *
     * @return 附加信息。
     */
    public String getSubInfo() {
        return subInfo;
    }

    /**
     * 设置主信息。
     *
     * @param info 主信息。
     */
    public void setMainInfo(final String info) {
        this.mainInfo = info;
    }

    /**
     * 设置归属地等附加信息。
     *
     * @param info 附加信息。
     */
    public void setSubInfo(final String info) {
        this.subInfo = info;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        return new StringBuilder(mainInfo).append(subInfo).toString();
    }
}
