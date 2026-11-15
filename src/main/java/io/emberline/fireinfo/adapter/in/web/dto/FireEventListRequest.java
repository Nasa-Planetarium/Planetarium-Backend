package io.emberline.fireinfo.adapter.in.web.dto;

import io.emberline.fireinfo.application.port.in.FireEventListQuery;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

/**
 * Query of GET /api/v1/fire-events (snake_case names bound with @BindParam; formats here, rules and the limits of
 * {@code emberline.limits.*} in the service).
 */
public record FireEventListRequest(
        @Parameter(name = "aoi_id") @BindParam("aoi_id") @Pattern(regexp = Patterns.AOI_ID, message = "must be an aoi_id") String aoiId,
        @Parameter(name = "bbox") @Size(max = 200) @Pattern(regexp = "^[0-9eE+.,\\- ]+$", message = "must be west,south,east,north") String bbox,
        @Parameter(name = "start") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String start,
        @Parameter(name = "end") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String end,
        @Parameter(name = "min_det") @BindParam("min_det") @Min(1) @Max(100000) Integer minDet,
        @Parameter(name = "sort") @Pattern(regexp = "^(sum_weight|start_date|burned_area_km2)$",
                message = "must be sum_weight, start_date or burned_area_km2") String sort,
        @Parameter(name = "limit") Integer limit,
        @Parameter(name = "cursor") @Size(max = 512) @Pattern(regexp = Patterns.CURSOR, message = "must be a cursor of this API") String cursor,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public FireEventListQuery toQuery() {
        return new FireEventListQuery(aoiId, bbox, start, end, minDet, sort, limit, cursor);
    }
}
