/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.vision;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 边界盒。
 */
public class BoundingBox implements JSONable {

    /** 左上角横坐标。 */
    public int x;

    /** 左上角纵坐标。 */
    public int y;

    /** 宽度。 */
    public int width;

    /** 高度。 */
    public int height;

    public BoundingBox() {
        this(0, 0, 1, 1);
    }

    public BoundingBox(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public BoundingBox(JSONObject json) {
        this.x = json.getInt("x");
        this.y = json.getInt("y");
        this.width = json.getInt("width");
        this.height = json.getInt("height");
    }

    /**
     * 获取右下角横坐标。
     *
     * @return 横坐标。
     */
    public int getX2() {
        return this.x + this.width;
    }

    /**
     * 获取右下角纵坐标。
     *
     * @return 纵坐标。
     */
    public int getY2() {
        return this.y + this.height;
    }

    /**
     * 获取宽高比。
     *
     * @return 宽除以高；高度为 0 时返回 0 。
     */
    public double getAspectRatio() {
        return ((double) this.width) / ((double) this.height);
    }

    /**
     * 获取中心点。
     *
     * @return 中心点坐标。
     */
    public Point getCenterPoint() {
        return new Point((int)(this.x + ((double)this.width * 0.5)),
                (int)(this.y + ((double)this.height * 0.5)));
    }

    /**
     * 对点进行碰撞检测。
     * @param x x。
     * @param y y。
     * @return 满足条件返回 {@code true} 。
     */
    public boolean detectCollision(int x, int y) {
        return (x > this.x && y > this.y && x < this.x + this.width && y < this.y + this.height);
    }

    /**
     * 边界盒碰撞检测。
     * @param box box。
     * @return 满足条件返回 {@code true} 。
     */
    public boolean detectCollision(BoundingBox box) {
        int mX = Math.max(this.x, box.x);
        int mY = Math.max(this.y, box.y);
        int mX2 = Math.min(this.getX2(), box.getX2());
        int mY2 = Math.min(this.getY2(), box.getY2());
        int w = mX2 - mX;
        int h = mY2 - mY;
        if (w > 0 && h > 0) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 计算面积。
     * @return 计算结果。
     */
    public int calculateArea() {
        return this.width * this.height;
    }

    /**
     * 将指定的边界盒与当前边界盒进行空间合并。
     * @param bbox bbox。
     * @return 处理结果。
     */
    public BoundingBox merge(BoundingBox bbox) {
        int x = Math.min(bbox.x, this.x);
        int y = Math.min(bbox.y, this.y);
        int x2 = Math.max(bbox.x + bbox.width, this.x + this.width);
        int y2 = Math.max(bbox.y + bbox.height, this.y + this.height);
        int width = x2 - x;
        int height = y2 - y;

        return new BoundingBox(x, y, width, height);
    }

    /**
     * 计算与指定边界盒的碰撞面积。
     * @param box box。
     * @return 计算结果。
     */
    public int calculateCollisionArea(BoundingBox box) {
        int mX = Math.max(this.x, box.x);
        int mY = Math.max(this.y, box.y);
        int mX2 = Math.min(this.getX2(), box.getX2());
        int mY2 = Math.min(this.getY2(), box.getY2());
        int w = mX2 - mX;
        int h = mY2 - mY;
        if (w <= 0 || h <= 0) {
            return 0;
        }
        return w * h;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        return "(" + x + "," + y + "," + width + "," + height + ")";
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        return this.toCompactJSON();
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = new JSONObject();
        json.put("x", this.x);
        json.put("y", this.y);
        json.put("width", this.width);
        json.put("height", this.height);
        return json;
    }
}
