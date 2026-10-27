/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

/**
 * 用于线程间异步传值。
 */
public class FileTypeMutable {

    /** 值。 */
    public FileType value = FileType.UNKNOWN;

    public FileTypeMutable() {
    }

    public FileTypeMutable(FileType value) {
        this.value = value;
    }

    /**
     * 获取值。
     *
     * @return 值。
     */
    public FileType getValue() {
        synchronized (this) {
            return this.value;
        }
    }

    /**
     * 设置值。
     *
     * @param value 值。
     */
    public void setValue(FileType value) {
        synchronized (this) {
            this.value = value;
        }
    }
}
