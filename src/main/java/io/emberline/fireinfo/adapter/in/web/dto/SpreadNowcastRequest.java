package io.emberline.fireinfo.adapter.in.web.dto;

import io.emberline.fireinfo.application.port.in.SpreadNowcastQuery;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

/** Query of GET /api/v1/spread-nowcast: exactly one of {@code aoi_id} / {@code bbox}, optional {@code min_level}. */
public record SpreadNowcastRequest(
        @Parameter(name = "aoi_id") @BindParam("aoi_id") @Pattern(regexp = Patterns.AOI_ID, message = "must be an aoi_id") String aoiId,
        @Parameter(name = "bbox") @Size(max = 200) @Pattern(regexp = "^[0-9eE+.,\\- ]+$", message = "must be west,south,east,north") String bbox,
        @Parameter(name = "min_level") @BindParam("min_level") @Pattern(regexp = "^(low|moderate|high)$",
                message = "must be low, moderate or high") String minLevel,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public SpreadNowcastQuery toQuery() {
        return new SpreadNowcastQuery(aoiId, bbox, minLevel);
    }
}
