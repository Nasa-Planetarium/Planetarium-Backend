package io.emberline.shared.error;

/** Data that is not loaded or not provided by the release (503 family). */
public class DataUnavailableException extends EmberlineException {

    public DataUnavailableException(ErrorCode errorCode, String param, String detail) {
        super(errorCode, param, detail);
    }

    public DataUnavailableException(ErrorCode errorCode, String detail) {
        super(errorCode, null, detail);
    }

    public DataUnavailableException(ErrorCode errorCode, String param, String detail, Throwable cause) {
        super(errorCode, param, detail, cause);
    }
}
