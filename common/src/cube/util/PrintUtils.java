/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

import cube.vision.Size;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * 打印实用函数。
 */
public class PrintUtils {

    /** A4 超高分辨率尺寸，3508×2480 。 */
    public final static Size PaperA4Ultra = new Size(3508, 2480);

    /** A4 常规尺寸，1754×1240 。 */
    public final static Size PaperA4Normal = new Size(1754, 1240);

    /** A4 小尺寸，1402×992 。 */
    public final static Size PaperA4Small = new Size(1402, 992);

    /**
     * 创建空白纸张画布。
     *
     * @param size 纸张尺寸。
     * @return 画布图像。
     */
    public static BufferedImage createPaper(Size size) {
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setBackground(Color.WHITE);
        graphics.clearRect(0, 0, size.width, size.height);
        graphics.dispose();
        return image;
    }

    /**
     * 在纸张画布上叠加内容。
     *
     * @param size    纸张尺寸。
     * @param content 待叠加的图像。
     * @param x       内容左上角横坐标。
     * @param y       内容左上角纵坐标。
     * @return 合成后的画布。
     */
    public static BufferedImage createPaper(Size size, BufferedImage content, int x, int y) {
        BufferedImage image = PrintUtils.createPaper(size);
        Graphics2D g2d = image.createGraphics();
        g2d.drawImage(content, x, y, content.getWidth(), content.getHeight(), null);
        g2d.dispose();
        return image;
    }
}
