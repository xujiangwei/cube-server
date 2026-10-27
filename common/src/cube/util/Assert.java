/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

/**
 * 辅助测试的断言类。
 */
public final class Assert {

    private Assert() {
    }

    /**
     * 断言两个字符串相等。
     *
     * @param expectedValue 期望值。
     * @param actualValue   实际值。
     */
    public static void equals(String expectedValue, String actualValue) {
        if (expectedValue.equals(actualValue)) {
            System.out.println("Assert OK - " + expectedValue);
            return;
        }

        System.err.println("Assert Failed : " + expectedValue + " != " + actualValue);
    }

    /**
     * 断言两个长整型相等。
     *
     * @param expectedValue 期望值。
     * @param actualValue   实际值。
     */
    public static void equals(Long expectedValue, Long actualValue) {
        if (expectedValue.longValue() == actualValue.longValue()) {
            System.out.println("Assert OK - " + expectedValue);
            return;
        }

        System.err.println("Assert Failed : " + expectedValue + " != " + actualValue);
    }

    /**
     * 断言两个整数相等，失败时输出标签。
     *
     * @param tag           断言标签，用于定位失败项。
     * @param expectedValue 期望值。
     * @param actualValue   实际值。
     */
    public static void equals(String tag, int expectedValue, int actualValue) {
        if (expectedValue == actualValue) {
            if (null != tag) {
                System.out.println(tag + " - Assert OK - " + expectedValue);
            }
            else {
                System.out.println("Assert OK - " + expectedValue);
            }
            return;
        }

        if (null != tag) {
            System.err.println(tag + " - Assert Failed : " + expectedValue + " != " + actualValue);
        }
        else {
            System.err.println("Assert Failed : " + expectedValue + " != " + actualValue);
        }
    }

    /**
     * 断言两个整数相等。
     *
     * @param expectedValue 期望值。
     * @param actualValue   实际值。
     */
    public static void equals(int expectedValue, int actualValue) {
        Assert.equals(null, expectedValue, actualValue);
    }

    /**
     * 断言两个布尔值相等，失败时输出标签。
     *
     * @param tag           断言标签，用于定位失败项。
     * @param expectedValue 期望值。
     * @param actualValue   实际值。
     */
    public static void equals(String tag, boolean expectedValue, boolean actualValue) {
        if (expectedValue == actualValue) {
            if (null != tag) {
                System.out.println(tag + " - Assert OK - " + expectedValue);
            }
            else {
                System.out.println("Assert OK - " + expectedValue);
            }
            return;
        }

        if (null != tag) {
            System.err.println(tag + " - Assert Failed : " + expectedValue + " != " + actualValue);
        }
        else {
            System.err.println("Assert Failed : " + expectedValue + " != " + actualValue);
        }
    }

    /**
     * 断言两个布尔值相等。
     *
     * @param expectedValue 期望值。
     * @param actualValue   实际值。
     */
    public static void equals(boolean expectedValue, boolean actualValue) {
        Assert.equals(null, expectedValue, actualValue);
    }
}
