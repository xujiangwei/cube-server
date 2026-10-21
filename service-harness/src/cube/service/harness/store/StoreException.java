/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

/**
 * 存储层稳定的内部错误。
 *
 * <p>所有存储失败都以稳定 {@code code} 向上传递；SQL 文本、连接串与凭据
 * 不得进入 API 响应或日志，调用方按 code 分支处理，message 仅重复 code 本身。
 */
public final class StoreException extends RuntimeException {
    /**
     * 稳定错误码（如 "FILE_BUSY"、"STORE_TARGET_REFUSED"），同时作为异常 message 原样暴露。
     */
    public final String code;

    /**
     * 以稳定错误码构造存储异常，无底层根因。
     *
     * @param code 稳定错误码，非空；同时写入 message 与 {@link #code} 字段
     */
    public StoreException(String code) {
        super(code);
        this.code = code;
    }

    /**
     * 以稳定错误码与底层根因构造存储异常。
     *
     * @param code  稳定错误码，非空；同时写入 message 与 {@link #code} 字段
     * @param cause 触发失败的底层异常（如 SQLException），仅用于内部脱敏日志，不向 API 暴露
     */
    public StoreException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }
}
