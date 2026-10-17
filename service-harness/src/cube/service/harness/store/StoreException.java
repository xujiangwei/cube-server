package cube.service.harness.store;

/** Stable internal errors. SQL text and credentials must not reach API/log responses. */
public final class StoreException extends RuntimeException {
    public final String code;

    public StoreException(String code) {
        super(code);
        this.code = code;
    }

    public StoreException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }
}
