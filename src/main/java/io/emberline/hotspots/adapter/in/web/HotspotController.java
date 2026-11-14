package io.emberline.hotspots.adapter.in.web;

import io.emberline.hotspots.adapter.in.web.dto.HotspotListRequest;
import io.emberline.hotspots.application.port.in.HotspotListResponse;
import io.emberline.hotspots.application.port.in.ListHotspotsUseCase;
import io.emberline.shared.release.ReleaseRef;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JSON, or CSV ({@code Accept: text/csv} or {@code format=csv}) with the same columns, written by {@link HotspotCsvConverter}. */
@RestController
@RequestMapping(path = "/api/v1")
class HotspotController {

    private final ListHotspotsUseCase listHotspots;

    HotspotController(ListHotspotsUseCase listHotspots) {
        this.listHotspots = listHotspots;
    }

    @GetMapping(path = "/hotspots", produces = {"application/json", "text/csv"})
    HotspotListResponse list(ReleaseRef release, @Valid @ParameterObject HotspotListRequest request) {
        return listHotspots.list(release, request.toQuery());
    }

    @GetMapping(path = "/hotspots", params = "format=csv", produces = "text/csv")
    HotspotListResponse csv(ReleaseRef release, @Valid @ParameterObject HotspotListRequest request) {
        return listHotspots.list(release, request.toQuery());
    }
}
