package io.emberline.catalog.adapter.in.web.dto;

import io.emberline.catalog.application.port.in.AoiListQuery;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

/** Query of GET /api/v1/aois (snake_case names bound with @BindParam; validation is here). */
public record AoiListRequest(
        @Parameter(name = "level") @Pattern(regexp = Patterns.AOI_LEVEL, message = "must be one of world, continent, country, adm1, kr_sido, kr_sgg, group") String level,
        @Parameter(name = "parent_id") @BindParam("parent_id") @Pattern(regexp = Patterns.AOI_ID, message = "must be an aoi_id") String parentId,
        @Parameter(name = "q") @Size(max = 100) String q,
        @Parameter(name = "include_hidden") @BindParam("include_hidden") Boolean includeHidden,
        @Parameter(name = "limit") @Min(1) @Max(1000) Integer limit,
        @Parameter(name = "cursor") @Size(max = 512) @Pattern(regexp = Patterns.CURSOR, message = "must be a cursor of this API") String cursor) {

    public AoiListQuery toQuery() {
        return new AoiListQuery(level, parentId, q, Boolean.TRUE.equals(includeHidden), limit == null ? 100 : limit, cursor);
    }
}
