/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

import cell.core.talk.dialect.ActionDialect;
import cell.util.Utils;

/**
 * 摆渡数据包。
 */
public class FerryPacket {

    /** 消息类数据包标识。 */
    public final static String MESSAGING = "Messaging";

    private long sn;

    private ActionDialect dialect;

    private String domain;

    public FerryPacket(ActionDialect dialect) {
        this.sn = Utils.generateSerialNumber();
        this.dialect = dialect;
    }

    public FerryPacket(String portName) {
        this.sn = Utils.generateSerialNumber();
        this.dialect = new ActionDialect(FerryAction.Ferry.name);
        this.dialect.addParam("port", portName);
    }

    /**
     * 获取序列号。
     *
     * @return 序列号。
     */
    public long getSN() {
        return this.sn;
    }

    /**
     * 设置域。
     *
     * @param domain 域。
     */
    public void setDomain(String domain) {
        this.domain = domain;
    }

    /**
     * 获取域。
     *
     * @return 域。
     */
    public String getDomain() {
        return this.domain;
    }

    /**
     * 获取动作方言。
     *
     * @return 方言对象。
     */
    public ActionDialect getDialect() {
        return this.dialect;
    }

    /**
     * 转换为动作方言。
     *
     * @return 方言对象。
     */
    public ActionDialect toDialect() {
        if (!this.dialect.containsParam("sn")) {
            this.dialect.addParam("sn", this.sn);
        }

        if (null != this.domain) {
            this.dialect.addParam("domain", this.domain);
        }

        return this.dialect;
    }
}
