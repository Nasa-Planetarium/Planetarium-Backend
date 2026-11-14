package io.emberline.external.adapter.in.web.dto;

import io.emberline.external.application.port.in.WmsTileQuery;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Query of GET /api/v1/firms/wms-tiles (the only accepted parameters; others are refused by the allow-list filter). */
public record WmsTileRequest(
        @Parameter(name = "layer", required = true) @NotNull(message = "is required")
        @Pattern(regexp = "^(viirs_noaa20_24h|viirs_noaa21_24h|modis_24h|viirs_noaa20_7d)$",
                message = "must be one of viirs_noaa20_24h, viirs_noaa21_24h, modis_24h, viirs_noaa20_7d") String layer,
        @Parameter(name = "bbox", required = true) @NotNull(message = "is required") @Size(max = 200)
        @Pattern(regexp = "^[0-9eE+.,\\- ]{7,200}$", message = "must be west,south,east,north") String bbox,
        @Parameter(name = "width", required = true) @NotNull(message = "is required") @Min(16) @Max(2048) Integer width,
        @Parameter(name = "height", required = true) @NotNull(message = "is required") @Min(16) @Max(2048) Integer height,
        @Parameter(name = "time") @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}(/\\d{4}-\\d{2}-\\d{2})?$",
                message = "must be YYYY-MM-DD or YYYY-MM-DD/YYYY-MM-DD") String time) {

    public WmsTileQuery toQuery() {
        return new WmsTileQuery(layer, bbox, width, height, time);
    }
}
