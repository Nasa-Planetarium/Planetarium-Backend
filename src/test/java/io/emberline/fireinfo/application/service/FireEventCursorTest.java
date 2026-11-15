package io.emberline.fireinfo.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emberline.fireinfo.domain.EventPosition;
import io.emberline.fireinfo.domain.EventSort;
import io.emberline.shared.error.ConflictException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** P2-4 (unit): the cursor is opaque, round-trips its keyset and refuses other releases and selections. */
class FireEventCursorTest {

    @Test
    void roundTripsEverySortKey() {
        for (EventPosition p : new EventPosition[] {
                new EventPosition(EventSort.SUM_WEIGHT, 0.92701, null, "KR_20001102_0001"),
                new EventPosition(EventSort.START_DATE, null, LocalDate.parse("2025-03-22"), "KR_20250322_0021"),
                new EventPosition(EventSort.BURNED_AREA_KM2, 97.24037900333937, null, "KR_20250323_0006"),
                new EventPosition(EventSort.BURNED_AREA_KM2, null, null, "ARG_20250101_0001")}) {
            String c = FireEventCursor.encode("v12#0123456789ab", "abc", p);
            assertThat(c).matches("^[A-Za-z0-9_-]+$");
            assertThat(FireEventCursor.decode(c, "v12#0123456789ab", "abc")).isEqualTo(p);
        }
    }

    @Test
    void otherReleaseIsStaleAndOtherSelectionInvalid() {
        String c = FireEventCursor.encode("v12#0123456789ab", "abc", new EventPosition(EventSort.SUM_WEIGHT, 1.0, null, "KR_20001102_0001"));
        assertThatThrownBy(() -> FireEventCursor.decode(c, "v12#ffffffffffff", "abc"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CURSOR_STALE));
        assertThatThrownBy(() -> FireEventCursor.decode(c, "v12#0123456789ab", "abd"))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.param()).isEqualTo("cursor"));
        assertThatThrownBy(() -> FireEventCursor.decode("bm90IGEgY3Vyc29y", "v12#0123456789ab", "abc"))
                .isInstanceOf(InvalidRequestException.class);
    }
}
