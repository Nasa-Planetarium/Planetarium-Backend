package io.emberline.catalog.application.port.in;

import java.util.List;

/** GET /api/v1/aois (contract schema AoiList). {@code count} = items in this page. */
public record AoiListResponse(String methodVersion, String unit, int count, String nextCursor, List<AoiItem> aois) {

    public AoiListResponse {
        aois = List.copyOf(aois);
    }
}
