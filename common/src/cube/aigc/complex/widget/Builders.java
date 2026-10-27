/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import cell.util.log.Logger;
import org.json.JSONObject;

/**
 * 控件构建器。
 *
 * <p>按 JSON 中的类型标识还原控件与动作实例，是反序列化的唯一入口。</p>
 */
public final class Builders {

    private Builders() {
    }

    /**
     * 按 JSON 构建控件。
     *
     * <p>读取名称字段分派到具体控件类，未知类型返回 {@code null} 。</p>
     *
     * @param json 控件 JSON 。
     * @return 控件实例；类型未知返回 {@code null} 。
     */
    public static Widget buildWidget(JSONObject json) {
        try {
            String name = json.getString("name");
            if (ListTile.NAME.equals(name)) {
                return new ListTile(json);
            }
            else if (ListView.NAME.equals(name)) {
                return new ListView(json);
            }
            else {
                Logger.w(Builders.class, "#buildWidget - Unknown name: " + name);
                return null;
            }
        } catch (Exception e) {
            Logger.e(Builders.class, "#buildWidget", e);
            return null;
        }
    }

    /**
     * 按 JSON 构建动作。
     *
     * @param json 动作 JSON 。
     * @return 动作实例；类型未知返回 {@code null} 。
     */
    public static Action buildAction(JSONObject json) {
        try {
            String name = json.getString("name");
            if (PromptAction.NAME.equals(name)) {
                return new PromptAction(json);
            }
            else {
                Logger.w(Builders.class, "#buildAction - Unknown name: " + name);
                return null;
            }
        } catch (Exception e) {
            Logger.e(Builders.class, "#buildAction", e);
            return null;
        }
    }
}
