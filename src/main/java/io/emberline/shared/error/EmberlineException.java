package io.emberline.shared.error;

import java.util.Objects;

/**
 * Base of every domain/application error. The subtype says the family; the {@link ErrorCode} says the case.
 * A new error is one enum constant plus one card — the advice does not change (OCP).
 * {@code detail} must be safe for clients: no paths, class names, SQL or keys.
 */
public abstract class EmberlineException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String param;

    protected EmberlineException(ErrorCode errorCode, String param, String detail) {
        super(detail);
        this.errorCode = Objects.requireNonNull(errorCode);
        this.param = param;
    }

    protected EmberlineException(ErrorCode errorCode, String param, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = Objects.requireNonNull(errorCode);
        this.param = param;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** The offending parameter (snake_case, as the client sent it), or null. */
    public String param() {
        return param;
    }

    public String detail() {
        return getMessage();
    }
}
