/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 联系人风险。
 *
 * 目前最大支持16位掩码。
 */
public final class ContactRisk {

    /** 禁止全部操作。 */
    public final static int FORBID_ALL = 1;

    /** 禁止登录。 */
    public final static int FORBID_SIGN_IN = 1 << 1;

    /** 禁止保存文件。 */
    public final static int FORBID_SAVE_FILE = 1 << 2;

    /** 禁止销毁文件。 */
    public final static int FORBID_DESTROY_FILE = 1 << 3;

    public ContactRisk() {
    }

    /**
     * 叠加风险位。
     *
     * @param mask 当前风险位。
     * @param risk 待叠加的风险位。
     * @return 叠加后的风险位。
     */
    public static int mask(int mask, int risk) {
        return mask | risk;
    }

    /**
     * 清除风险位。
     *
     * @param mask 当前风险位。
     * @param risk 待清除的风险位。
     * @return 清除后的风险位。
     */
    public static int unmask(int mask, int risk) {
        return mask & (65535 - risk);
    }

    /**
     * 判断是否全部禁止。
     *
     * @param mask 全部禁止。
     * @return 全部禁止。
     */
    public static boolean hasForbiddenAll(int mask) {
        return  (mask & FORBID_ALL) != 0;
    }

    /**
     * 判断是否禁止登录。
     *
     * @param mask 禁止登录。
     * @return 禁止登录。
     */
    public static boolean hasForbiddenSignIn(int mask) {
        return (mask & FORBID_SIGN_IN) != 0;
    }

    /**
     * 判断是否禁止保存文件。
     *
     * @param mask 禁止保存文件。
     * @return 禁止保存文件。
     */
    public static boolean hasForbiddenSaveFile(int mask) {
        return (mask & FORBID_SAVE_FILE) != 0;
    }

    /**
     * 判断是否禁止销毁文件。
     *
     * @param mask 禁止销毁文件。
     * @return 禁止销毁文件。
     */
    public static boolean hasForbiddenDestroyFile(int mask) {
        return (mask & FORBID_DESTROY_FILE) != 0;
    }

    /**
     * 解释为 JSON 结构描述。
     * @param mask mask。
     * @return 处理结果。
     */
    public static JSONObject toJSON(int mask) {
        JSONObject json = new JSONObject();
        json.put("forbidAll", ContactRisk.hasForbiddenAll(mask));
        json.put("forbidSignIn", ContactRisk.hasForbiddenSignIn(mask));
        json.put("forbidSaveFile", ContactRisk.hasForbiddenSaveFile(mask));
        json.put("forbidDestroyFile", ContactRisk.hasForbiddenDestroyFile(mask));
        return json;
    }

    /**
     * 通过 JSON 结果解释为掩码。
     * @param mask mask。
     * @param json JSON 数据。
     * @return 处理结果。
     */
    public static int toMask(int mask, JSONObject json) {
        int resultMask = mask;
        if (json.has("forbidAll")) {
            boolean value = json.getBoolean("forbidAll");
            if (value) {
                resultMask = ContactRisk.mask(resultMask, FORBID_ALL);
            }
            else {
                resultMask = ContactRisk.unmask(resultMask, FORBID_ALL);
            }
        }
        if (json.has("forbidSignIn")) {
            boolean value = json.getBoolean("forbidSignIn");
            if (value) {
                resultMask = ContactRisk.mask(resultMask, FORBID_SIGN_IN);
            }
            else {
                resultMask = ContactRisk.unmask(resultMask, FORBID_SIGN_IN);
            }
        }
        if (json.has("forbidSaveFile")) {
            boolean value = json.getBoolean("forbidSaveFile");
            if (value) {
                resultMask = ContactRisk.mask(resultMask, FORBID_SAVE_FILE);
            }
            else {
                resultMask = ContactRisk.unmask(resultMask, FORBID_SAVE_FILE);
            }
        }
        if (json.has("forbidDestroyFile")) {
            boolean value = json.getBoolean("forbidDestroyFile");
            if (value) {
                resultMask = ContactRisk.mask(resultMask, FORBID_DESTROY_FILE);
            }
            else {
                resultMask = ContactRisk.unmask(resultMask, FORBID_DESTROY_FILE);
            }
        }
        return resultMask;
    }


    /**
     * 本地自测入口。
     *
     * <p>验证风险位的叠加与清除结果。</p>
     *
     * @param args 未使用。
     */
    public static void main(String[] args) {
        int mask = 0;
        System.out.println("SignIn - E:false - A:" + ContactRisk.hasForbiddenSignIn(mask));
        System.out.println("SaveFile - E:false - A:" + ContactRisk.hasForbiddenSaveFile(mask));

        // 标记
        mask = ContactRisk.mask(mask, ContactRisk.FORBID_ALL);
        mask = ContactRisk.mask(mask, ContactRisk.FORBID_SIGN_IN);
        System.out.println("SignIn - E:true - A:" + ContactRisk.hasForbiddenSignIn(mask));
        System.out.println("SaveFile - E:false - A:" + ContactRisk.hasForbiddenSaveFile(mask));

        // 取消
        mask = ContactRisk.unmask(mask, ContactRisk.FORBID_SIGN_IN);
        System.out.println("SignIn - E:false - A:" + ContactRisk.hasForbiddenSignIn(mask));
        System.out.println("SaveFile - E:false - A:" + ContactRisk.hasForbiddenSaveFile(mask));

        mask = ContactRisk.mask(mask, ContactRisk.FORBID_SAVE_FILE);
        mask = ContactRisk.mask(mask, ContactRisk.FORBID_SIGN_IN);
        System.out.println("SignIn - E:true - A:" + ContactRisk.hasForbiddenSignIn(mask));
        System.out.println("SaveFile - E:true - A:" + ContactRisk.hasForbiddenSaveFile(mask));
    }
}
