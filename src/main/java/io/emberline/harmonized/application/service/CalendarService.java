package io.emberline.harmonized.application.service;

import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.harmonized.application.port.in.CalendarMonth;
import io.emberline.harmonized.application.port.in.CalendarQuery;
import io.emberline.harmonized.application.port.in.CalendarResponse;
import io.emberline.harmonized.application.port.in.GetCalendarUseCase;
import io.emberline.harmonized.domain.AoiMonths;
import io.emberline.harmonized.domain.MonthRecord;
import io.emberline.harmonized.domain.Reading;
import io.emberline.harmonized.domain.cases.ReadCaseRegistry;
import io.emberline.shared.Units;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.release.ReleaseRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Calendar: validate the year range, resolve the AOI (404 before any cache), cut the cached bundle in memory. */
@Service
class CalendarService implements GetCalendarUseCase {

    static final String ROUTE = "calendar";

    private final CatalogApi catalog;
    private final AoiMonthsProvider months;
    private final int maxYears;

    CalendarService(CatalogApi catalog, AoiMonthsProvider months, EmberlineProperties properties) {
        this.catalog = catalog;
        this.months = months;
        this.maxYears = properties.limits().calendarYears();
    }

    @Override
    public CalendarResponse calendar(ReleaseRef release, String aoiId, CalendarQuery query) {
        checkRange(query.startYear(), query.endYear());
        AoiSummary aoi = catalog.requireAoi(release, aoiId, "aoi_id");
        AoiMonths bundle = months.get(release, aoi.aoiId(), ROUTE);
        int start = Optional.ofNullable(query.startYear()).or(bundle::firstYear).orElse(2000);
        int end = Optional.ofNullable(query.endYear()).or(bundle::lastYear).orElse(start);
        checkRange(start, end);
        List<CalendarMonth> out = new ArrayList<>((end - start + 1) * 12);
        for (int y = start; y <= end; y++) {
            for (int m = 1; m <= 12; m++) {
                out.add(month(bundle, y, m));
            }
        }
        return new CalendarResponse(aoi.aoiId(), release.methodVersion(), Units.UNIT, start, end, List.of(), out, aoi.nameKo());
    }

    private void checkRange(Integer start, Integer end) {
        if (start == null || end == null) {
            return;
        }
        if (start > end) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "start_year", "start_year must not be after end_year");
        }
        if (end - start + 1 > maxYears) {
            throw new InvalidRequestException(ErrorCode.CALENDAR_RANGE_TOO_LARGE, "end_year",
                    "a calendar covers at most " + maxYears + " years; narrow start_year..end_year");
        }
    }

    static CalendarMonth month(AoiMonths bundle, int year, int month) {
        Optional<MonthRecord> row = bundle.at(year, month);
        if (row.isEmpty()) {
            Reading r = Reading.outOfRange();
            return new CalendarMonth(year, month, null, null, null, null, null, null, null, null, null, r.status(), null,
                    null, null, null, null, List.of(), null, r.flags());
        }
        MonthRecord m = row.get();
        Reading r = ReadCaseRegistry.apply(m.reading());
        return new CalendarMonth(year, month, r.value(), r.valueLo(), r.valueHi(), r.valueProjected(), r.asOfDate(),
                m.rawModis(), m.rawViirs(), m.valueSource(), m.dataSource(), r.status(), m.nCells(), r.verdict(),
                m.percentile(), m.ratioToMedian(), m.lowSample(), List.of(), null, r.flags());
    }
}
