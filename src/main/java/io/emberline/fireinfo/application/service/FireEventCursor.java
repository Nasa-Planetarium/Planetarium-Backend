package io.emberline.fireinfo.application.service;

import io.emberline.fireinfo.domain.EventPosition;
import io.emberline.fireinfo.domain.EventSort;
import io.emberline.shared.error.ConflictException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Opaque keyset cursor of /fire-events: base64url("e1|data_version|selection|sort|value|date|event_id") — the last
 * event of the page by the sort key and event_id. {@code selection} fingerprints area, period, min_det and sort, so a
 * cursor only continues the list it came from (any limit); a cursor of another release → 409 cursor_stale.
 */
final class FireEventCursor {

    private static final String PREFIX = "e1";

    private FireEventCursor() {
    }

    static String encode(String dataVersion, String selection, EventPosition at) {
        String text = String.join("|", PREFIX, dataVersion, selection, at.sort().code(),
                at.value() == null ? "" : Double.toString(at.value()), at.date() == null ? "" : at.date().toString(), at.eventId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static EventPosition decode(String cursor, String dataVersion, String selection) {
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (parts.length != 7 || !PREFIX.equals(parts[0])) {
            throw invalid();
        }
        if (!parts[1].equals(dataVersion)) {
            throw new ConflictException(ErrorCode.CURSOR_STALE, "cursor",
                    "the release changed since this cursor was issued; start again without cursor");
        }
        if (!parts[2].equals(selection)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "cursor",
                    "cursor belongs to another selection (area, start, end, min_det or sort); start again without cursor");
        }
        EventSort sort = EventSort.ofCode(parts[3]).orElseThrow(FireEventCursor::invalid);
        try {
            Double value = parts[4].isEmpty() ? null : Double.valueOf(parts[4]);
            LocalDate date = parts[5].isEmpty() ? null : LocalDate.parse(parts[5]);
            if (parts[6].isEmpty() || (sort == EventSort.START_DATE && date == null) || (sort == EventSort.SUM_WEIGHT && value == null)) {
                throw invalid();
            }
            return new EventPosition(sort, value, date, parts[6]);
        } catch (NumberFormatException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException(ErrorCode.INVALID_PARAM, "cursor", "cursor is not a cursor of this API");
    }
}
