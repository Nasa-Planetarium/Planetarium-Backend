package io.emberline.harmonized.application.port.in;

import io.emberline.shared.release.ReleaseRef;

/** GET /api/v1/aois/{aoiId}/calendar — year × month from aoi_month + aoi_month_compare, values as stored. */
public interface GetCalendarUseCase {

    CalendarResponse calendar(ReleaseRef release, String aoiId, CalendarQuery query);
}
