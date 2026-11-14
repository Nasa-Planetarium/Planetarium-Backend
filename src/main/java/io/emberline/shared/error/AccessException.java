package io.emberline.shared.error;

/** Authentication or authorization failures (401/403 family). */
public class AccessException extends EmberlineException {

    public AccessException(ErrorCode errorCode, String param, String detail) {
        super(errorCode, param, detail);
    }

    public AccessException(ErrorCode errorCode, String detail) {
        super(errorCode, null, detail);
    }

    public AccessException(ErrorCode errorCode, String param, String detail, Throwable cause) {
        super(errorCode, param, detail, cause);
    }
}
