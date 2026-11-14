package io.emberline.ingest.domain;

import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.util.regex.Pattern;

/** Release folder name: {@code ^[a-z0-9][a-z0-9._-]{0,63}$} (no separators, no "..", checked before any file access). */
public record ReleaseKey(String value) {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    public ReleaseKey {
        if (value == null || !VALID.matcher(value).matches() || value.contains("..")) {
            throw new InvalidRequestException(ErrorCode.RELEASE_KEY_INVALID, "release_key",
                    "release_key must match ^[a-z0-9][a-z0-9._-]{0,63}$");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
