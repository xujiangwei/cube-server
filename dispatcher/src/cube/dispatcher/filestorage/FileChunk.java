/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.filestorage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件数据块。
 */
public class FileChunk implements Comparable<FileChunk> {

    /**
     * 文件块关联的联系人 ID 。
     */
    protected long contactId;

    /**
     * 文件块关联的域。
     */
    protected String domain;

    /**
     * 文件块当前对应的令牌。
     */
    protected String token;

    /**
     * 文件名。
     */
    protected String fileName;

    /**
     * 文件大小。
     */
    protected long fileSize;

    /**
     * 文件最后修改时间。
     */
    protected long lastModified;

    /**
     * 当前数据库对应的文件的游标。
     */
    protected long cursor;

    /**
     * 当前数据库包含的数据长度。
     */
    protected int size;

    /**
     * 当前数据库在整体数据中的结束位置。
     */
    protected long position;

    /**
     * 当前文件块的数据。
     */
    private byte[] data;

    /**
     * 当前文件块数据的临时文件。
     */
    private Path dataFile;

    /**
     * 构造文件块。
     *
     * @param contactId    所属联系人 ID 。
     * @param domain       所属域。
     * @param token        访问令牌。
     * @param fileName     文件名。
     * @param fileSize     文件总大小，单位字节。
     * @param lastModified 文件最后修改时间戳，单位毫秒。
     * @param cursor       本块在文件中的起始偏移。
     * @param size         本块字节数。
     * @param data         本块数据。
     */
    public FileChunk(long contactId, String domain, String token, String fileName,
                     long fileSize, long lastModified, long cursor, int size, byte[] data) {
        this.contactId = contactId;
        this.domain = domain;
        this.token = token;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.lastModified = lastModified;
        this.cursor = cursor;
        this.size = size;
        this.data = data;
        this.position = cursor + size;
    }

    /**
     * 将数据写到临时文件。
     */
    public void flush(Path path, String filename) {
        this.dataFile = Paths.get(path.toString(), filename);
        try {
            Files.write(this.dataFile, this.data);
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.data = null;
    }

    /**
     * 获取数据块内容。
     *
     * <p>内存中没有数据时从临时文件延迟加载，避免大文件上传时把所有分片都留在内存。</p>
     *
     * @return 数据块内容；读取失败返回 {@code null} 。
     */
    public byte[] getData() {
        if (null == this.data && null != this.dataFile) {
            try {
                return Files.readAllBytes(this.dataFile);
            } catch (IOException e) {
                return null;
            }
        }
        else {
            return this.data;
        }
    }

    /**
     * 释放数据块占用的内存与临时文件。
     *
     * <p>写入磁盘或转发完成后调用，可立即降低内存占用。</p>
     */
    public void clear() {
        this.data = null;
        if (null != this.dataFile) {
            try {
                Files.delete(this.dataFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 判断两个数据块是否相同。
     *
     * <p>依次比较联系人、域、文件名与偏移量，四者相同才算同一块。</p>
     *
     * @param object 待比较对象。
     * @return 相同返回 {@code true} 。
     */
    @Override
    public boolean equals(Object object) {
        if (null != object && object instanceof FileChunk) {
            FileChunk other = (FileChunk) object;
            if (other.cursor == this.cursor) {
                return true;
            }
        }

        return false;
    }

    /**
     * 按起始偏移量排序。
     *
     * <p>用于把乱序到达的分片按文件位置重新排列。</p>
     *
     * @param other 另一个数据块。
     * @return 偏移量之差。
     */
    @Override
    public int compareTo(FileChunk other) {
        return (int) (this.cursor - other.cursor);
    }
}
