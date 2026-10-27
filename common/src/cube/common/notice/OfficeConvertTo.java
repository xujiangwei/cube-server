/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import cube.common.action.FileProcessorAction;
import org.json.JSONObject;

/**
 * 文档转换。
 */
public class OfficeConvertTo extends NoticeData {

    /** 通知动作名：转换 Office 文档。 */
    public final static String ACTION = FileProcessorAction.OfficeConvertTo.name;

    /** 请求参数：域名。 */
    public final static String DOMAIN = "domain";

    /** 请求参数：源文件码。 */
    public final static String FILE_CODE = "fileCode";

    /** 请求参数：转换参数。 */
    public final static String PARAMETER = "parameter";

    /** 请求参数：目标格式。 */
    public final static String FORMAT = "format";

    public OfficeConvertTo(String domain, String fileCode, String format) {
        super(ACTION);
        this.put(DOMAIN, domain);
        this.put(FILE_CODE, fileCode);

        JSONObject parameter = new JSONObject();
        parameter.put(FORMAT, format);

        this.put(PARAMETER, parameter);
    }
}
