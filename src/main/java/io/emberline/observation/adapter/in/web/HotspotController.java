package io.emberline.observation.adapter.in.web;

import io.emberline.observation.adapter.in.web.dto.HotspotsRequest;
import io.emberline.observation.application.port.in.HotspotList;
import io.emberline.observation.application.port.in.ListHotspotsUseCase;
import io.emberline.shared.release.ReleaseRef;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
class HotspotController {

    private final ListHotspotsUseCase listHotspots;

    HotspotController(ListHotspotsUseCase listHotspots) {
        this.listHotspots = listHotspots;
    }

    @GetMapping("/hotspots")
    HotspotList hotspots(ReleaseRef release, @Valid @ParameterObject HotspotsRequest request) {
        return listHotspots.hotspots(release, request.toQuery());
    }
}
