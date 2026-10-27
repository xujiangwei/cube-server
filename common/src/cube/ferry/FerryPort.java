/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

/**
 * Port 动作描述。
 * 主要用于 Ferry 和 Tenet 两个 Action 的数据操作。
 */
public class FerryPort {

    /** 动作：转入成员。 */
    public final static String TransferIntoMember = "TransferIntoMember";

    /** 动作：转出成员。 */
    public final static String TransferOutMember = "TransferOutMember";

    /** 动作：重置授权。 */
    public final static String ResetLicence = "ResetLicence";

    /** 动作：写入消息。 */
    public final static String WriteMessage = "WriteMessage";

    /** 更新消息。 */
    public final static String UpdateMessage = "UpdateMessage";

    /** 动作：删除消息。 */
    public final static String DeleteMessage = "DeleteMessage";

    /** 动作：焚毁消息。 */
    public final static String BurnMessage = "BurnMessage";

    /** 保存文件。 */
    public final static String SaveFile = "SaveFile";

    /** 动作：清理。 */
    public final static String Cleanup = "Cleanup";

    private FerryPort() {
    }
}
