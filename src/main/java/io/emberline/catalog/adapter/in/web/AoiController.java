package io.emberline.catalog.adapter.in.web;

import io.emberline.catalog.adapter.in.web.dto.AoiListRequest;
import io.emberline.catalog.application.port.in.AoiDetailResponse;
import io.emberline.catalog.application.port.in.AoiListResponse;
import io.emberline.catalog.application.port.in.BoundaryFeature;
import io.emberline.catalog.application.port.in.GetAoiUseCase;
import io.emberline.catalog.application.port.in.ListAoisUseCase;
import io.emberline.shared.json.GeoJson;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.validation.Patterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
class AoiController {

    private final ListAoisUseCase listAois;
    private final GetAoiUseCase getAoi;

    AoiController(ListAoisUseCase listAois, GetAoiUseCase getAoi) {
        this.listAois = listAois;
        this.getAoi = getAoi;
    }

    @GetMapping("/aois")
    AoiListResponse list(ReleaseRef release, @Valid @ParameterObject AoiListRequest request) {
        return listAois.list(release, request.toQuery());
    }

    @GetMapping("/aois/{aoiId}")
    AoiDetailResponse get(ReleaseRef release, @PathVariable("aoiId") @Pattern(regexp = Patterns.AOI_ID) String aoiId) {
        return getAoi.get(release, aoiId);
    }

    @GetMapping(path = "/aois/{aoiId}/boundary", produces = {GeoJson.MEDIA_TYPE, "application/json"})
    BoundaryFeature boundary(ReleaseRef release, @PathVariable("aoiId") @Pattern(regexp = Patterns.AOI_ID) String aoiId) {
        return getAoi.boundary(release, aoiId);
    }
}
