package io.emberline.harmonized.application.port.in;

import io.emberline.shared.release.ReleaseRef;

/** GET /api/v1/aois/{aoiId}/summary — month rows from aoi_month, day rows and partial months from harmonized_cell_day. */
public interface GetSummaryUseCase {

    SummaryResponse summary(ReleaseRef release, String aoiId, SummaryQuery query);
}
