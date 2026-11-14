package io.emberline.hotspots.application.service;

import io.emberline.hotspots.domain.PagePosition;
import io.emberline.hotspots.domain.SortKey;
import io.emberline.shared.error.ConflictException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Opaque keyset cursor: base64url("h1|data_version|query|acq_us|latitude|longitude|satellite|skip"). {@code query}
 * fingerprints the selection (area, dates, layer), so a cursor only continues the request it came from; a cursor of
 * another release → 409 cursor_stale.
 */
final class HotspotCursor {

    private static final String PREFIX = "h1";

    private HotspotCursor() {
    }

    static String encode(String dataVersion, String query, PagePosition at) {
        SortKey k = at.key();
        String text = String.join("|", PREFIX, dataVersion, query,
                Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, k.acqDatetimeUtc())), Double.toString(k.latitude()),
                Double.toString(k.longitude()), k.satellite(), Integer.toString(at.skip()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static PagePosition decode(String cursor, String dataVersion, String query) {
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (parts.length != 8 || !PREFIX.equals(parts[0])) {
            throw invalid();
        }
        if (!parts[1].equals(dataVersion)) {
            throw new ConflictException(ErrorCode.CURSOR_STALE, "cursor",
                    "the release changed since this cursor was issued; start again without cursor");
        }
        if (!parts[2].equals(query)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "cursor",
                    "cursor belongs to another selection (area, start, end or layer); start again without cursor");
        }
        try {
            Instant acq = Instant.EPOCH.plus(Long.parseLong(parts[3]), ChronoUnit.MICROS);
            int skip = Integer.parseInt(parts[7]);
            if (skip < 1) {
                throw invalid();
            }
            return new PagePosition(new SortKey(acq, Double.parseDouble(parts[4]), Double.parseDouble(parts[5]), parts[6]), skip);
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalid();
        }
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException(ErrorCode.INVALID_PARAM, "cursor", "cursor is not a cursor of this API");
    }
}
