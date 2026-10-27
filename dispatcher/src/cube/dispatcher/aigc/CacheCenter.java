/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.aigc;

import cell.util.log.Logger;
import cube.aigc.psychology.Painting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AIGC 结果缓存中心。
 *
 * <p>缓存绘画结果与二进制数据，落盘到 {@code cache/} 目录。
 * 内存中的绘画缓存达到上限时，按时间戳淘汰最旧条目并同步删除对应文件。
 * 以单例提供，内部按文件码索引，允许跨线程并发读写。</p>
 */
public final class CacheCenter {

    private final static CacheCenter instance = new CacheCenter();

    private final File cachePath = new File("cache/");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int maxCache = 5000;

    private final List<String> processingFileList = new ArrayList<>();
    private final Map<String, Painting> paintingMap = new ConcurrentHashMap<>();
    private final Map<String, File> fileMap = new ConcurrentHashMap<>();

    private CacheCenter() {
        if (!this.cachePath.exists()) {
            this.cachePath.mkdirs();
        }
        Logger.i(this.getClass(), "Cache path: " + this.cachePath.getAbsolutePath());
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
     * 判断文件是否正在处理。
     *
     * <p>用于阻止同一文件被重复提交处理。</p>
     *
     * @param fileCode 文件码。
     * @return 处理中返回 {@code true} 。
     */
    public boolean isProcessing(String fileCode) {
        synchronized (this) {
            return this.processingFileList.contains(fileCode);
        }
    }

    /**
     * 标记文件进入处理中。
     *
     * @param fileCode 文件码。
     */
    public void markProcessing(String fileCode) {
        synchronized (this) {
            this.processingFileList.add(fileCode);
        }
    }

    /**
     * 清除文件的处理中标记。
     *
     * @param fileCode 文件码。
     */
    public void finishProcessing(String fileCode) {
        synchronized (this) {
            this.processingFileList.remove(fileCode);
        }
    }

    /**
     * 获取缓存的绘画结果。
     *
     * @param fileCode 文件码。
     * @return 绘画结果；未缓存时返回 {@code null} 。
     */
    public Painting getPainting(String fileCode) {
        return this.paintingMap.get(fileCode);
    }

    /**
     * 缓存绘画结果。
     *
     * <p>同时清除处理中标记，并在缓存超限时触发淘汰。</p>
     *
     * @param fileCode 文件码。
     * @param painting 绘画结果。
     */
    public void cache(String fileCode, Painting painting) {
        synchronized (this) {
            this.processingFileList.remove(fileCode);
        }
        this.paintingMap.put(fileCode, painting);
        tickCache();
    }

    /**
     * 读取缓存的二进制数据。
     *
     * <p>内存未命中时回落到磁盘读取；文件不存在时清理索引并返回 {@code null} 。</p>
     *
     * @param fileCode 文件码。
     * @return 文件内容；不存在或读取失败时返回 {@code null} 。
     */
    public byte[] getData(String fileCode) {
        File file = this.fileMap.get(fileCode);
        if (null == file) {
            // 从本地磁盘加载
            file = new File(this.cachePath, fileCode);
        }
        if (!file.exists()) {
            this.fileMap.remove(fileCode);
            return null;
        }
        try {
            return Files.readAllBytes(Paths.get(file.getAbsolutePath()));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 缓存二进制数据。
     *
     * <p>先写磁盘，确认文件存在后再建立索引；写盘失败只记录堆栈，不向上抛出。</p>
     *
     * @param fileCode 文件码。
     * @param data     文件内容。
     */
    public void cache(String fileCode, byte[] data) {
        try {
            File file = new File(this.cachePath, fileCode);
            Files.write(Paths.get(file.getAbsolutePath()), data);
            if (file.exists()) {
                this.fileMap.put(fileCode, file);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 淘汰缓存直至低于上限。
     *
     * <p>用 {@code running} 保证同一时刻只有一个淘汰线程，避免并发清理互相踩踏。
     * 每次淘汰时间戳最旧的一条，连同磁盘文件一起删除。</p>
     */
    protected synchronized void tickCache() {
        if (!this.running.get()) {
            this.running.set(true);

            (new Thread(new Runnable() {
                @Override
                public void run() {
                    while (paintingMap.size() >= maxCache) {
                        long minTimestamp = System.currentTimeMillis();
                        String key = null;
                        Iterator<Map.Entry<String, Painting>> iter = paintingMap.entrySet().iterator();
                        while (iter.hasNext()) {
                            Map.Entry<String, Painting> entry = iter.next();
                            if (entry.getValue().timestamp < minTimestamp) {
                                minTimestamp = entry.getValue().timestamp;
                                key = entry.getKey();
                            }
                        }
                        if (null != key) {
                            paintingMap.remove(key);
                            File file = fileMap.remove(key);
                            if (null != file && file.exists()) {
                                file.delete();
                            }
                        }
                    }

                    running.set(false);
                }
            })).start();
        }
    }
}
