package io.emberline.harmonized.adapter.in.web;

import io.emberline.harmonized.adapter.in.web.dto.CalendarRequest;
import io.emberline.harmonized.adapter.in.web.dto.SummaryRequest;
import io.emberline.harmonized.application.port.in.CalendarResponse;
import io.emberline.harmonized.application.port.in.ComparisonResponse;
import io.emberline.harmonized.application.port.in.GetCalendarUseCase;
import io.emberline.harmonized.application.port.in.GetComparisonUseCase;
import io.emberline.harmonized.application.port.in.GetSummaryUseCase;
import io.emberline.harmonized.application.port.in.SummaryResponse;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.validation.Patterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calendar, comparison, summary. On the routes with a request record the aoi_id path variable is validated by
 * CatalogApi.requireAoi (400 invalid_param, param aoi_id) instead of {@code @Pattern}, so the record's field errors keep
 * their own param names (a path constraint would switch the whole method to method validation).
 */
@RestController
@RequestMapping(path = "/api/v1/aois/{aoiId}", produces = "application/json")
class HarmonizedController {

    static final String YEAR_MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

    private final GetCalendarUseCase calendar;
    private final GetComparisonUseCase comparison;
    private final GetSummaryUseCase summary;

    HarmonizedController(GetCalendarUseCase calendar, GetComparisonUseCase comparison, GetSummaryUseCase summary) {
        this.calendar = calendar;
        this.comparison = comparison;
        this.summary = summary;
    }

    @GetMapping(path = "/calendar", produces = {"application/json", "text/csv"})
    CalendarResponse calendar(ReleaseRef release, @PathVariable("aoiId") String aoiId,
                              @Valid @ParameterObject CalendarRequest request) {
        return calendar.calendar(release, aoiId, request.toQuery());
    }

    @GetMapping("/months/{yearMonth}/comparison")
    ComparisonResponse comparison(ReleaseRef release, @PathVariable("aoiId") @Pattern(regexp = Patterns.AOI_ID) String aoiId,
                                  @PathVariable("yearMonth") @Pattern(regexp = YEAR_MONTH, message = "must be YYYY-MM") String yearMonth) {
        return comparison.comparison(release, aoiId, yearMonth);
    }

    @GetMapping(path = "/summary", produces = {"application/json", "text/csv"})
    SummaryResponse summary(ReleaseRef release, @PathVariable("aoiId") String aoiId,
                            @Valid @ParameterObject SummaryRequest request) {
        return summary.summary(release, aoiId, request.toQuery());
    }
}
