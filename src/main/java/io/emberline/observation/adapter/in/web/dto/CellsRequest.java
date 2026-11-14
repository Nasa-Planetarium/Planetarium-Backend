package io.emberline.observation.adapter.in.web.dto;

import io.emberline.observation.application.port.in.CellsQuery;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

/** Query of GET /api/v1/cells (exactly one of aoi_id / bbox is checked by the use case). */
public record CellsRequest(
        @Parameter(name = "bbox") @Size(max = 200) @Pattern(regexp = BBOX, message = "must be west,south,east,north") String bbox,
        @Parameter(name = "aoi_id") @BindParam("aoi_id") @Pattern(regexp = Patterns.AOI_ID, message = "must be an aoi_id") String aoiId,
        @Parameter(name = "month", required = true) @NotNull(message = "is required") @Pattern(regexp = YEAR_MONTH, message = "must be YYYY-MM") String month) {

    static final String BBOX = "^[0-9eE+.,\\- ]{7,200}$";
    static final String YEAR_MONTH = "^(20\\d{2}|2100)-(0[1-9]|1[0-2])$";

    public CellsQuery toQuery() {
        return new CellsQuery(aoiId, bbox, month);
    }
}
