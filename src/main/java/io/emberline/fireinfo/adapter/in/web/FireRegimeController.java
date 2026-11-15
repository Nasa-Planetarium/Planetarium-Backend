package io.emberline.fireinfo.adapter.in.web;

import io.emberline.fireinfo.adapter.in.web.dto.FireRegimeRequest;
import io.emberline.fireinfo.application.port.in.FireRegimeResponse;
import io.emberline.fireinfo.application.port.in.GetAoiFireRegimeUseCase;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.http.LayerCached;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fire regime badge months of an AOI (contract getAoiFireRegime). JSON only — the contract lists no text/csv for it. */
@RestController
@RequestMapping(path = "/api/v1/aois/{aoiId}")
class FireRegimeController {

    private final GetAoiFireRegimeUseCase regime;

    FireRegimeController(GetAoiFireRegimeUseCase regime) {
        this.regime = regime;
    }

    @LayerCached
    @GetMapping(path = "/fire-regime", produces = "application/json")
    FireRegimeResponse regime(ReleaseRef release, @PathVariable("aoiId") String aoiId,
                              @Valid @ParameterObject FireRegimeRequest request) {
        return regime.regime(release, aoiId, request.toQuery());
    }
}
