package io.emberline.outlook.adapter.in.web.dto;

import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.BindParam;

/** Query of GET /api/v1/occurrence/aois ({@code parent_id} required; an unknown id is 404 from the catalog). */
public record AoiOccurrenceListRequest(
        @Parameter(name = "parent_id", required = true) @BindParam("parent_id") @NotBlank(message = "is required")
        @Pattern(regexp = Patterns.AOI_ID, message = "must be an aoi_id") String parentId,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {
}
