package io.emberline.observation.application.port.in;

import java.time.LocalDate;

/**
 * GET /api/v1/cells/frp after format validation: aoiId / bbox as given (exactly one is checked by the use case),
 * start..end inclusive (date_local), freq {@code day} or {@code month}.
 */
public record CellFrpQuery(String aoiId, String bbox, LocalDate start, LocalDate end, String freq) {
}
