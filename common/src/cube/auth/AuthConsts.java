/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.auth;

/**
 * 授权常量。
 *
 * <p>集中定义令牌字段名与分隔符，避免各处硬编码不一致。</p>
 */
public final class AuthConsts {

    /**
     * 默认域。
     */
    public static String DEFAULT_DOMAIN = "default_domain";

    /**
     * 默认 App ID 。
     */
    public static String DEFAULT_APP_ID = "CubeApp";

    /**
     * 默认 App Key 。
     */
    public static String DEFAULT_APP_KEY = "default-opensource-appkey";

    /**
     * 格式化常量说明。
     *
     * @return 便于排查的常量描述。
     */
    public static String formatString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Default domain : ").append(AuthConsts.DEFAULT_DOMAIN).append("\n");
        buf.append("Default app id : ").append(AuthConsts.DEFAULT_APP_ID).append("\n");
        buf.append("Default app key: ").append(AuthConsts.DEFAULT_APP_KEY);
        return buf.toString();
    }
}
