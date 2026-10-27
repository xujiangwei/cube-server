/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

import cell.util.Utils;

import java.util.Arrays;

/**
 * 浮点数组工具。
 *
 * <p>提供随机数、Softmax、归一化与缩放等常用运算，供特征向量预处理使用。</p>
 */
public final class FloatUtils {

    private FloatUtils() {
    }

    /**
     * 生成区间内的随机数。
     *
     * @param floor 下界。
     * @param ceil  上界。
     * @return 落在闭区间内的随机值。
     */
    public static double random(double floor, double ceil) {
        double scale = 10000.0f;
        int num = Utils.randomInt((int)(floor * scale), (int)(ceil * scale));
        return ((double) num) / scale;
    }

    /**
     * 对数组做 Softmax 变换。
     *
     * <p>输出各项非负且和为 1，用于把原始分数转为概率分布。</p>
     *
     * @param input 输入数组。
     * @return 概率数组。
     */
    public static double[] softmax(double[] input) {
        double[] output = new double[input.length];
        double total = Arrays.stream(input).map(Math::exp).sum();
        for (int i = 0; i < input.length; ++i) {
            output[i] = Math.exp(input[i]) / total;
        }
        return output;
    }

    /**
     * 把数组归一化到 0 到 1 。
     *
     * @param input 输入数组。
     * @return 归一化后的数组。
     */
    public static double[] normalization(double[] input) {
        double inputMax = Arrays.stream(input).max().getAsDouble();
        double inputMin = Arrays.stream(input).min().getAsDouble();
        double[] output = new double[input.length];
        for (int i = 0; i < input.length; ++i) {
            output[i] = (input[i] - inputMin) / (inputMax - inputMin);
        }
        return output;
    }

    /**
     * 按指定上下界归一化。
     *
     * @param input 输入数组。
     * @param min   下界。
     * @param max   上界。
     * @return 归一化后的数组。
     */
    public static double[] normalization(double[] input, double min, double max) {
        double inputMax = Arrays.stream(input).max().getAsDouble();
        double inputMin = Arrays.stream(input).min().getAsDouble();
        if (inputMin == inputMax && min == 0) {
            return null;
        }
        double[] output = new double[input.length];
        for (int i = 0; i < input.length; ++i) {
            output[i] = (max - min) * (input[i] - inputMin) / (inputMax - inputMin) + min;
        }
        return output;
    }

    /**
     * 按最大值缩放数组。
     *
     * @param input 输入数组。
     * @param max   缩放基准值。
     * @return 缩放后的数组。
     */
    public static double[] scale(double[] input, double max) {
        double inputMax = Arrays.stream(input).max().getAsDouble();
        double scale = (max / inputMax);
        double[] output = new double[input.length];
        for (int i = 0; i < input.length; ++i) {
            output[i] = input[i] * scale;
        }
        return output;
    }

    /**
     * 把包装类型数组转为基本类型数组。
     *
     * @param array 包装类型数组。
     * @return 基本类型数组。
     */
    public static double[] toArray(Double[] array) {
        double[] result = new double[array.length];
        for (int i = 0; i < array.length; ++i) {
            result[i] = array[i];
        }
        return result;
    }

    /**
     * 本地自测入口。
     *
     * @param args 未使用。
     */
    public static void main(String[] args) {
        double[] input = new double[] {
                -2, 1, 7, -1, 3
        };

        double[] output = FloatUtils.normalization(input);
        for (double v : output) {
            System.out.println(v);
        }
        System.out.println("----------------------------------------");


    }
}
