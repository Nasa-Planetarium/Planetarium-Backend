package io.emberline.shared.error;

/** A resource that does not exist in the pinned release (404 family). */
public class ResourceNotFoundException extends EmberlineException {

    public ResourceNotFoundException(ErrorCode errorCode, String param, String detail) {
        super(errorCode, param, detail);
    }

    public ResourceNotFoundException(ErrorCode errorCode, String detail) {
        super(errorCode, null, detail);
    }

    public ResourceNotFoundException(ErrorCode errorCode, String param, String detail, Throwable cause) {
        super(errorCode, param, detail, cause);
    }
}
