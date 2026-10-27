/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.filestorage;

/**
 * 区块标签。
 */
public class FileChunkTag implements Comparable<FileChunkTag> {

    /** 所属联系人 ID 。 */
    protected long contactId;

    /** 所属域。 */
    protected String domain;

    /** 文件名。 */
    protected String fileName;

    /** 文件码。 */
    protected String fileCode;

    private int hashCode = 0;

    public FileChunkTag(FileChunk chunk, String fileCode) {
        this.contactId = chunk.contactId;
        this.domain = chunk.domain;
        this.fileName = chunk.fileName;
        this.fileCode = fileCode;
    }

    /**
     * 按联系人、域与文件名判断相等。
     *
     * <p>文件码不参与比较：同一个文件的不同版本共享同一标签。</p>
     *
     * @param object 待比较对象。
     * @return 三者都相同返回 {@code true} 。
     */
    @Override
    public boolean equals(Object object) {
        if (object instanceof FileChunkTag) {
            FileChunkTag other = (FileChunkTag) object;
            return other.contactId == this.contactId &&
                    other.domain.equals(this.domain) &&
                    other.fileName.equals(this.fileName);
        }

        return false;
    }

    /**
     * 计算哈希值。
     *
     * <p>首次计算后缓存，标签在生命周期内不可变，缓存是安全的。</p>
     *
     * @return 基于联系人、域与文件名的哈希值。
     */
    @Override
    public int hashCode() {
        if (0 == this.hashCode) {
            Long id = this.contactId;
            this.hashCode = id.hashCode() + this.domain.hashCode() + this.fileName.hashCode();
        }

        return this.hashCode;
    }

    /**
     * 按哈希值比较大小。
     *
     * <p>相等条件与 {@link #equals(Object)} 一致，便于放进有序集合去重。</p>
     *
     * @param other 另一个标签。
     * @return 相同时返回 0 ，否则返回哈希值之差。
     */
    @Override
    public int compareTo(FileChunkTag other) {
        if (other.contactId == this.contactId &&
                other.domain.equals(this.domain) &&
                other.fileName.equals(this.fileName)) {
            return 0;
        }
        else {
            return this.hashCode() - other.hashCode();
        }
    }
}
