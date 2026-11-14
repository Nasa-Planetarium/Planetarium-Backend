package io.emberline.shared.error;

/** Input that cannot be served (400/413 family). */
public class InvalidRequestException extends EmberlineException {

    public InvalidRequestException(ErrorCode errorCode, String param, String detail) {
        super(errorCode, param, detail);
    }

    public InvalidRequestException(ErrorCode errorCode, String detail) {
        super(errorCode, null, detail);
    }

    public InvalidRequestException(ErrorCode errorCode, String param, String detail, Throwable cause) {
        super(errorCode, param, detail, cause);
    }
}
