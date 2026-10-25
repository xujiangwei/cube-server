/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.fileprocessor;

import cell.util.log.Logger;
import cube.common.entity.Image;
import cube.processor.Subprocess;
import cube.util.FileType;
import cube.util.FileUtils;
import cube.vision.Size;
import net.coobird.thumbnailator.Thumbnails;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 图片工具。
 */
public final class ImageTools {

    public static String WORKING_PATH = "storage/tmp/";

    /**
     * 探测 ImageMagick 是否可用的超时：15 秒。
     */
    private final static long CHECK_TIMEOUT_MILLIS = 15 * 1000L;

    /**
     * 读取图片信息的超时：30 秒。
     */
    private final static long IDENTIFY_TIMEOUT_MILLIS = 30 * 1000L;

    /**
     * 单次图片转换的超时：2 分钟。
     */
    private final static long CONVERT_TIMEOUT_MILLIS = 2 * 60 * 1000L;

    private static boolean USE_IMAGEMAGICK = true;

    private ImageTools() {
    }

    public static boolean check() {
        Subprocess.Result result = Subprocess.execute(Arrays.asList("convert", "-help"), null,
                CHECK_TIMEOUT_MILLIS);

        // convert -help 的正常退出码是 1，这里接受 0 与 1
        int exitCode = result.getExitCode();
        if (result.isStarted() && !result.isTimedOut() && (0 == exitCode || 1 == exitCode)) {
            USE_IMAGEMAGICK = true;
            Logger.i(ImageTools.class, "Use ImageMagick for processing image data");
        }
        else {
            USE_IMAGEMAGICK = false;
            Logger.i(ImageTools.class, "Use Java Image IO for processing image data - " + result);
        }

        return USE_IMAGEMAGICK;
    }

    /**
     * 识别图片。
     *
     * @param fullpath
     * @return 不支持的文件类型返回 {@code null} 值。
     */
    public static Image identify(String fullpath) {
        Image image = null;

        if (USE_IMAGEMAGICK) {
            List<String> command = new ArrayList<>();
            command.add("identify");
            command.add("-format");
            command.add("%m %w %h ");
            command.add(fullpath);

            Subprocess.Result result = Subprocess.execute(command, null, IDENTIFY_TIMEOUT_MILLIS);
            if (!result.isSuccess()) {
                Logger.w(ImageTools.class, "#identify - Failed: " + fullpath + " - " + result);
                return null;
            }

            for (String line : result.getOutput()) {
                if (line.length() > 0) {
                    String[] tmp = line.split(" ");
                    if (tmp.length == 3 || line.startsWith("GIF")) {
                        FileType type = FileType.matchExtension(tmp[0]);
                        if (type == FileType.JPEG || type == FileType.PNG || type == FileType.GIF || type == FileType.BMP) {
                            try {
                                image = new Image(type, Integer.parseInt(tmp[1]), Integer.parseInt(tmp[2]));
                            } catch (NumberFormatException e) {
                                Logger.w(ImageTools.class, "#identify - Invalid output line: " + line);
                            }
                        }
                    }
                }
            }
        }
        else {
            FileType fileType = FileUtils.extractFileExtensionType(fullpath);
            try {
               BufferedImage source = ImageIO.read(new File(fullpath));
               image = new Image(fileType, source.getWidth(), source.getHeight());
               source = null;
            } catch (IOException e) {
                Logger.w(ImageTools.class, "#identify - Failed to read image: " + fullpath, e);
            }
        }

        return image;
    }

    /**
     * 生成缩略图。
     *
     * @param inputFile
     * @param inputImageSize
     * @param outputFile
     * @return
     */
    public static Image thumbnail(String inputFile, Size inputImageSize, String outputFile, int quality) {
        if (USE_IMAGEMAGICK) {
            // 创建命令
            ArrayList<String> command = new ArrayList<>();
            command.add("convert");
            command.add(inputFile);

            if (inputImageSize.width > 1200 || inputImageSize.height > 1200) {
                command.add("-sample");
                command.add("1200");
                command.add("-quality");
                command.add("90");
            }
            else {
                command.add("-quality");
                command.add(Integer.toString(quality));
            }

            command.add(outputFile + ".jpg");

            Subprocess.Result result = Subprocess.execute(command, null, CONVERT_TIMEOUT_MILLIS);
            if (!result.isSuccess()) {
                Logger.w(ImageTools.class, "#thumbnail - " + result);
            }

            File file = new File(outputFile + ".jpg");
            if (file.exists()) {
                return ImageTools.identify(file.getAbsolutePath());
            }
            else {
                return null;
            }
        }
        else {
            int size = inputImageSize.width;
            if (inputImageSize.width > 1000 || inputImageSize.height > 1000) {
                size = 1000;
            }

            try {
                Thumbnails.of(new File(inputFile))
                        .outputQuality(((float)quality) / 100.0f)
                        .size(size, size)
                        .toFile(outputFile + ".jpg");
            } catch (IOException e) {
                e.printStackTrace();
            }

            File file = new File(outputFile + ".jpg");
            if (file.exists()) {
                return ImageTools.identify(file.getAbsolutePath());
            }
            else {
                return null;
            }
        }
    }

    /**
     * 生成缩略图。
     *
     * @param inputFile
     * @param outputFile
     * @param size
     * @return
     */
    public static Image thumbnailResize(String inputFile, String outputFile, int size) {
        if (USE_IMAGEMAGICK) {
            List<String> command = new ArrayList<>();
            command.add("convert");
            command.add(inputFile);
            command.add("-thumbnail");
            command.add(size + "x" + size);
            command.add(outputFile + ".jpg");

            Subprocess.Result result = Subprocess.execute(command, null, CONVERT_TIMEOUT_MILLIS);
            if (!result.isSuccess()) {
                Logger.w(ImageTools.class, "#thumbnailResize - " + result);
            }

            File file = new File(outputFile + ".jpg");
            if (file.exists()) {
                return ImageTools.identify(file.getAbsolutePath());
            }
            else {
                return null;
            }
        }
        else {
            try {
                Thumbnails.of(new File(inputFile))
                        .size(size, size)
                        .toFile(outputFile + ".jpg");
            } catch (IOException e) {
                e.printStackTrace();
            }

            File file = new File(outputFile + ".jpg");
            if (file.exists()) {
                return ImageTools.identify(file.getAbsolutePath());
            }
            else {
                return null;
            }
        }
    }

//    public static void main(String[] args) {
//        Image image = ImageTools.identify("/Users/ambrose/Documents/Repositories/Cube3/cube-server/README.md");
//        System.out.println(image);
//
//        image = ImageTools.identify("/Users/ambrose/Documents/Repositories/Cube3/assets/illustrations/screenshot_classroom_1.jpg");
//        System.out.println(image);
//
//        image = ImageTools.identify("/Users/ambrose/Documents/Repositories/Cube3/assets/illustrations/tutorial_facemonitor_1.png");
//        System.out.println(image);
//
//        image = ImageTools.identify("/Users/ambrose/Documents/Repositories/Cube3/assets/showcase/cloud_file.gif");
//        System.out.println(image);

//        Image image = ImageTools.thumbnail("/Users/ambrose/Documents/Repositories/Cube3/assets/illustrations/Cube3Framework.png",
//                "service/storage/tmp/t1", 480);
//        System.out.println(image);

//        ImageTools.check();
//    }
}
