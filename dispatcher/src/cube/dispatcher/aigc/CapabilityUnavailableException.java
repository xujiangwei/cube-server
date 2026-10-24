package cube.dispatcher.aigc;

/** A routing failure, distinct from invalid credentials or service business errors. */
public final class CapabilityUnavailableException extends RuntimeException {
    public CapabilityUnavailableException(String message) {
        super(message);
    }
}
