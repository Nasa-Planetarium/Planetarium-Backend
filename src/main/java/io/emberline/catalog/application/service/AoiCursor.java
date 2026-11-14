package io.emberline.catalog.application.service;

import io.emberline.shared.error.ConflictException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque keyset cursor: base64url("a1|<data_version>|<last aoi_id>"). A cursor of another release → 409 cursor_stale. */
final class AoiCursor {

    private static final String PREFIX = "a1";

    private AoiCursor() {
    }

    static String encode(String dataVersion, String lastAoiId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((PREFIX + "|" + dataVersion + "|" + lastAoiId).getBytes(StandardCharsets.UTF_8));
    }

    /** The last aoi_id of the previous page. */
    static String decode(String cursor, String dataVersion) {
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "cursor", "cursor is not a cursor of this API");
        }
        String[] parts = text.split("\\|", 3);
        if (parts.length != 3 || !PREFIX.equals(parts[0]) || parts[2].isEmpty()) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "cursor", "cursor is not a cursor of this API");
        }
        if (!parts[1].equals(dataVersion)) {
            throw new ConflictException(ErrorCode.CURSOR_STALE, "cursor",
                    "the release changed since this cursor was issued; start again without cursor");
        }
        return parts[2];
    }
}
