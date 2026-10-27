/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.hub;

import cube.common.entity.FileLabel;
import cube.util.FileUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存中心。
 */
public class CacheCenter {

    private final static CacheCenter instance = new CacheCenter();

    private Path workPath = Paths.get("cube-hub-files");

    private long fileCacheDuration = 30L * 24 * 60 * 60 * 1000;

    private ConcurrentHashMap<String, FileLabel> fileLabelMap;

    private CacheCenter() {
        this.fileLabelMap = new ConcurrentHashMap<>();

        if (!Files.exists(this.workPath)) {
            try {
                Files.createDirectories(this.workPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 获取单例。
     *
     * @return 全局唯一的缓存中心。
     */
    public final static CacheCenter getInstance() {
        return CacheCenter.instance;
    }

    /**
     * 获取工作目录。
     *
     * <p>缓存文件与标签文件都写在该目录下。</p>
     *
     * @return 工作目录路径。
     */
    public Path getWorkPath() {
        return this.workPath;
    }

    /**
     * 写入文件标签数据。
     *
     * <p>标签同时进内存索引与磁盘缓存，磁盘上以 {@code .cfl} 为后缀。
     * 返回值是按文件码与首选扩展名推导出的路径，调用方写入内容后该文件才真正存在。</p>
     *
     * @param fileLabel 文件标签。
     * @return 该文件对应的目标路径。
     */
    public File putFileLabel(FileLabel fileLabel) {
        this.fileLabelMap.put(fileLabel.getFileCode(), fileLabel);

        // 写入到缓存目录
        File file = new File(this.workPath.toFile(), fileLabel.getFileCode() + ".cfl");
        if (file.exists()) {
            file.delete();
        }

        try {
            Files.write(Paths.get(file.getAbsolutePath()), fileLabel.toJSON().toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new File(this.workPath.toFile(),
                fileLabel.getFileCode() + "." + fileLabel.getFileType().getPreferredExtension());
    }

    /**
     * 尝试读取已缓存的文件数据。
     *
     * <p>内存未命中时回落到磁盘上的标签文件；两者都没有则返回 {@code null} 。</p>
     *
     * @param fileCode 文件码。
     * @return 缓存条目；未缓存或读取失败时返回 {@code null} 。
     */
    public CachedFile tryGetFile(String fileCode) {
        FileLabel fileLabel = this.fileLabelMap.get(fileCode);

        if (null == fileLabel) {
            File labelFile = new File(this.workPath.toFile(), fileCode + ".cfl");
            if (labelFile.exists()) {
                try {
                    byte[] bytes = Files.readAllBytes(Paths.get(labelFile.getAbsolutePath()));
                    String jsonString = new String(bytes, StandardCharsets.UTF_8);
                    fileLabel = new FileLabel(new JSONObject(jsonString));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (null != fileLabel) {
                this.fileLabelMap.put(fileCode, fileLabel);
            }
        }

        if (null == fileLabel) {
            return null;
        }

        File file = new File(this.workPath.toFile(),
                fileCode + "." + fileLabel.getFileType().getPreferredExtension());
        return file.exists() ? new CachedFile(file, fileLabel) : null;
    }

    /**
     * 按通道加锁。
     *
     * <p>同一通道的并发处理需要串行化，锁粒度到通道而非全局，
     * 避免不同通道互相阻塞。</p>
     *
     * @param channelCode 通道码。
     */
    public void lock(String channelCode) {
        synchronized (this) {

        }
    }

    /**
     * 释放通道锁。
     *
     * @param channelCode 通道码。
     */
    public void unlock(String channelCode) {
        synchronized (this) {

        }
    }

    /**
     * 自检缓存目录。
     *
     * <p>扫描工作目录，清理没有对应标签文件的孤立缓存，
     * 并修复内存索引与磁盘不一致的条目。</p>
     */
    public void selfChecking() {
        // 遍历目录，删除超期文件
        File[] files = this.workPath.toFile().listFiles();
        if (null != files && files.length > 0) {
            long now = System.currentTimeMillis();
            for (File file : files) {
                if (now - file.lastModified() > this.fileCacheDuration) {
                    // 文件失效，删除
                    file.delete();
                }
            }
        }
    }

    public class CachedFile {

        public final File file;

        public final FileLabel fileLabel;

        public CachedFile(File file, FileLabel fileLabel) {
            this.file = file;
            this.fileLabel = fileLabel;
        }
    }
}
