package io.emberline.outlook.adapter.in.web.dto;

import io.emberline.outlook.application.port.in.GridOccurrenceQuery;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

/** Query of GET /api/v1/occurrence/grid (snake_case names bound with @BindParam; formats here, rules in the service). */
public record GridOccurrenceRequest(
        @Parameter(name = "bbox") @Size(max = 200) @Pattern(regexp = "^[0-9eE+.,\\- ]+$", message = "must be west,south,east,north") String bbox,
        @Parameter(name = "min_p_any") @BindParam("min_p_any") @DecimalMin("0") @DecimalMax("1") Double minPAny,
        @Parameter(name = "fields") @Pattern(regexp = "^(map|full)$", message = "must be map or full") String fields,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public GridOccurrenceQuery toQuery() {
        return new GridOccurrenceQuery(bbox, minPAny, fields);
    }
}
