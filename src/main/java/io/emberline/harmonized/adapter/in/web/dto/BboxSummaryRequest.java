package io.emberline.harmonized.adapter.in.web.dto;

import io.emberline.harmonized.application.port.in.BboxSummaryQuery;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Query of GET /api/v1/summaries: bbox plus the AOI summary's start/end/freq/format (formats here, rules in the service). */
public record BboxSummaryRequest(
        @Parameter(name = "bbox") @NotNull @Size(max = 200) @Pattern(regexp = "^[0-9eE+.,\\- ]+$", message = "must be west,south,east,north") String bbox,
        @Parameter(name = "start") @NotNull @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String start,
        @Parameter(name = "end") @NotNull @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String end,
        @Parameter(name = "freq") @Pattern(regexp = "^(month|day)$", message = "must be month or day") String freq,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public BboxSummaryQuery toQuery() {
        return new BboxSummaryQuery(bbox, new SummaryRequest(start, end, freq, format).toQuery());
    }
}
