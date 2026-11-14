package io.emberline.shared.error;

/** An upstream (FIRMS) failure (502/503 family). */
public class UpstreamException extends EmberlineException {

    public UpstreamException(ErrorCode errorCode, String param, String detail) {
        super(errorCode, param, detail);
    }

    public UpstreamException(ErrorCode errorCode, String detail) {
        super(errorCode, null, detail);
    }

    public UpstreamException(ErrorCode errorCode, String param, String detail, Throwable cause) {
        super(errorCode, param, detail, cause);
    }
}
