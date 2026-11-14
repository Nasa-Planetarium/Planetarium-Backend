package io.emberline.observation.adapter.in.web.dto;

import io.emberline.observation.application.port.in.HotspotsQuery;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

/** Query of GET /api/v1/hotspots (contract parameters; the route is 「준비 중」 until the release ships points). */
public record HotspotsRequest(
        @Parameter(name = "bbox") @Size(max = 200) @Pattern(regexp = CellsRequest.BBOX, message = "must be west,south,east,north") String bbox,
        @Parameter(name = "aoi_id") @BindParam("aoi_id") @Pattern(regexp = Patterns.AOI_ID, message = "must be an aoi_id") String aoiId,
        @Parameter(name = "start", required = true) @NotNull(message = "is required") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String start,
        @Parameter(name = "end", required = true) @NotNull(message = "is required") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String end,
        @Parameter(name = "layer") @Pattern(regexp = "^(reference|all)$", message = "must be reference or all") String layer,
        @Parameter(name = "limit") @Min(1) @Max(10000) Integer limit,
        @Parameter(name = "cursor") @Size(max = 512) @Pattern(regexp = Patterns.CURSOR, message = "must be a cursor of this API") String cursor,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public HotspotsQuery toQuery() {
        return new HotspotsQuery(bbox, aoiId, start, end, layer == null ? "reference" : layer, limit == null ? 1000 : limit,
                cursor, format);
    }
}
