package io.emberline.shared.error;

/** A state conflict (409/412 family, release transitions). */
public class ConflictException extends EmberlineException {

    public ConflictException(ErrorCode errorCode, String param, String detail) {
        super(errorCode, param, detail);
    }

    public ConflictException(ErrorCode errorCode, String detail) {
        super(errorCode, null, detail);
    }

    public ConflictException(ErrorCode errorCode, String param, String detail, Throwable cause) {
        super(errorCode, param, detail, cause);
    }
}
