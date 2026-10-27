/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

/**
 * 算法版本号。
 */
public class AlgorithmVersion {

    /** 主版本号。 */
    public final static int MAJOR = 0;

    /** 次版本号。 */
    public final static int MINOR = 77;

    /** 修订号。 */
    public final static int REVISION = 0;

    private AlgorithmVersion() {
    }

    /**
     * 转换为版本字符串。
     *
     * @return 版本字符串。
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
