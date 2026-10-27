/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.filestorage;

import cell.util.Base64;
import cell.util.log.Logger;
import cube.util.CodeUtils;
import cube.vision.Color;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 二维码文件辅助操作函数库。
 */
public class QRCodeHelper {

    private QRCodeHelper() {
    }

    /**
     * 获取二维码图片的 Base64 数据。
     *
     * <p>结果按分享码与协议前缀缓存为 {@code .base64} 文件，重复调用直接复用；
     * 首次生成时会临时写出 PNG 文件，编码完成后立即删除。
     * HTTPS 与 HTTP 使用不同的缓存文件（后缀 {@code _s} 与 {@code _n} ），避免两种链接互相覆盖。</p>
     *
     * @param workPath    工作目录，二维码资源写在此目录下。
     * @param sharingCode 分享码，同时作为缓存文件名。
     * @param url         二维码承载的地址。
     * @return Base64 编码的 PNG 数据；生成或读取失败时返回 {@code null} 。
     */
    public static String getQRCodeImageBase64(Path workPath, String sharingCode, String url) {
        boolean secure = url.indexOf("https://") == 0;
        String base64 = null;
        File file = new File(workPath.toFile(),
                sharingCode + "_" + (secure ? "s" : "n") + ".base64");
        if (!file.exists()) {
            // 创建二维码图片
            File qrCodeFile = new File(workPath.toFile(), sharingCode + "_" + (secure ? "s" : "n") + ".png");
            CodeUtils.generateQRCode(qrCodeFile, url,
                    300, 300, new Color(5, 5, 5));

            if (qrCodeFile.exists()) {
                byte[] data = null;
                try {
                    data = Files.readAllBytes(Paths.get(qrCodeFile.getAbsolutePath()));
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // 删除图片文件
                qrCodeFile.delete();

                // 将文件数据编码为 Base64
                base64 = Base64.encodeBytes(data);
                try {
                    Files.write(Paths.get(file.getAbsolutePath()),
                            base64.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        if (null == base64) {
            try {
                byte[] data = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
                base64 = new String(data, StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return base64;
    }

    /**
     * 获取二维码图片的字节内容。
     *
     * <p>复用 {@link #getQRCodeImageBase64} 的缓存结果并解码，避免重复生成图片。</p>
     *
     * @param workPath    工作目录。
     * @param sharingCode 分享码。
     * @param url         二维码承载的地址。
     * @return PNG 字节；生成失败返回 {@code null} 。
     */
    public static byte[] getQRCodeImageData(Path workPath, String sharingCode, String url) {
        String base64 = QRCodeHelper.getQRCodeImageBase64(workPath, sharingCode, url);
        byte[] data = null;
        try {
            data = Base64.decode(base64);
        } catch (IOException e) {
            Logger.w(QRCodeHelper.class, "#getQRCodeImageData", e);
        }
        return data;
    }
}
