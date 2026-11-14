package io.emberline.harmonized.application.port.out;

import io.emberline.harmonized.domain.AoiMonths;
import io.emberline.shared.release.ReleaseRef;

/** Every aoi_month (+ aoi_month_compare) row of one AOI in the pinned release — exactly one SQL statement. */
public interface AoiMonthReadPort {

    /** {@code route} labels the emberline_sql_statements_total metric. */
    AoiMonths load(ReleaseRef release, String aoiId, String route);
}
