/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 复合资源基类。
 */
public abstract class ComplexResource implements JSONable {

    public enum Subject {

        /**
         * 文件。
         */
        File,

        /**
         * 超链接。
         */
        Hyperlink,

        /**
         * 图表。
         */
        Chart,

        /**
         * 界面组件。
         */
        Widget,

        /**
         * 附录。
         */
        Attachment,

        ;

        public static Subject parse(String name) {
            if (name.equalsIgnoreCase(Subject.File.name())) {
                return File;
            }
            if (name.equalsIgnoreCase(Subject.Hyperlink.name())) {
                return Hyperlink;
            }
            else if (name.equalsIgnoreCase(Subject.Chart.name())) {
                return Chart;
            }
            else if (name.equalsIgnoreCase(Subject.Widget.name())) {
                return Widget;
            }
            else if (name.equalsIgnoreCase(Subject.Attachment.name())) {
                return Attachment;
            }
            else {
                return null;
            }
        }
    }

    /** 主题。 */
    protected final Subject subject;

    /** 序列号。 */
    public final long sn;

    protected ComplexResource(Subject subject) {
        this.subject = subject;
        this.sn = Utils.generateSerialNumber();
    }

    protected ComplexResource(Subject subject, JSONObject data) {
        this.subject = subject;
        this.sn = data.has("sn") ? data.getLong("sn") : Utils.generateSerialNumber();
    }

    /**
     * 获取主题。
     *
     * @return 主题。
     */
    public Subject getSubject() {
        return this.subject;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("subject", this.subject.name());
        json.put("sn", this.sn);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>用于传输体积敏感的场合，字段较完整序列化更少。</p>
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = new JSONObject();
        json.put("subject", this.subject.name());
        json.put("sn", this.sn);
        return json;
    }
}
