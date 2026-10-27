/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher;

/**
 * 版本信息。
 */
public final class Version {

    /** 主版本号。 */
    public final static int MAJOR = 3;

    /** 次版本号。 */
    public final static int MINOR = 0;

    /** 修订号，随每次发布递增。 */
    public final static int REVISION = 162;

    private Version() {
    }

    /**
     * 转为 {@code 主版本.次版本.修订号} 形式的版本串。
     *
     * @return 版本号字符串。
     */
    public static String toVersionString() {
        StringBuilder buf = new StringBuilder();
        buf.append(MAJOR);
        buf.append(".");
        buf.append(MINOR);
        buf.append(".");
        buf.append(REVISION);
        return buf.toString();
    }
}
