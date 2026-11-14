package io.emberline.shared.release.load;

import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;

/** A load that failed on the data (e.g. a duplicate key found by the unique index). Becomes a reason code. */
public class ReleaseLoadException extends InvalidRequestException {

    private final String reason;

    public ReleaseLoadException(String reason, Throwable cause) {
        super(ErrorCode.RELEASE_INVALID, null, "release load failed: " + reason, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
