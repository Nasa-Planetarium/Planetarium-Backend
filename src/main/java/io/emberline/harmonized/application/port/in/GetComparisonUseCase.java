package io.emberline.harmonized.application.port.in;

import io.emberline.shared.release.ReleaseRef;

/** GET /api/v1/aois/{aoiId}/months/{yearMonth}/comparison — the stored aoi_month_compare row, never recomputed. */
public interface GetComparisonUseCase {

    ComparisonResponse comparison(ReleaseRef release, String aoiId, String yearMonth);
}
