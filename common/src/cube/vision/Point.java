/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.vision;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 点描述。
 */
public class Point implements JSONable {

    /** 横坐标。 */
    public double x;

    /** 纵坐标。 */
    public double y;

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public Point(JSONObject json) {
        this.x = json.getDouble("x");
        this.y = json.getDouble("y");
    }

    /**
     * 计算到另一点的距离。
     *
     * @param other 目标点。
     * @return 欧氏距离。
     */
    public double distance(Point other) {
        return Math.sqrt((other.x - x) * (other.x - x) + (other.y - y) * (other.y - y));
    }

    /**
     * 按比例缩放坐标。
     *
     * @param value 缩放系数。
     * @return 缩放后的点。
     */
    public Point scale(double value) {
        return new Point(this.x * value, this.y * value);
    }

    /**
     * 计算两点之间的向量。
     *
     * @param startPoint 起点。
     * @param endPoint   终点。
     * @return 向量。
     */
    public static Point vector(Point startPoint, Point endPoint) {
        return new Point(endPoint.x - startPoint.x,
                endPoint.y - startPoint.y);
    }

    /**
     * 返回多边形质心。
     *
     * @param points 多边形各个点。
     * @return 返回多边形质心。
     */
    public static Point getCentroid(Point[] points) {
        double sumX = 0;
        double sumY = 0;

        for (Point p : points) {
            sumX += p.x;
            sumY += p.y;
        }

        return new Point(sumX / (double) points.length, sumY / (double) points.length);
    }

    /**
     * 判断点相对有向直线的位置。
     *
     * <p>返回叉积符号：正数在左侧、负数在右侧、0 在线上，用于几何方向判断。</p>
     *
     * @param startPoint 直线起点。
     * @param endPoint   直线终点。
     * @param basePoint  待判断点。
     * @return 叉积结果。
     */
    public static double pointLocation(Point startPoint, Point endPoint, Point basePoint) {
        Point vector = Point.vector(startPoint, endPoint);
        Point sV = Point.vector(startPoint, basePoint);
        return vector.x * sV.y - vector.y * sV.x;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(this.x);
        buf.append(",");
        buf.append(this.y);
        return buf.toString();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("x", this.x);
        json.put("y", this.y);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
