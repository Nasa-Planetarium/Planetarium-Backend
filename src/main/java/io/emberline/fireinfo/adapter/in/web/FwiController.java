package io.emberline.fireinfo.adapter.in.web;

import io.emberline.fireinfo.adapter.in.web.dto.FwiRequest;
import io.emberline.fireinfo.application.port.in.FwiResponse;
import io.emberline.fireinfo.application.port.in.GetAoiFwiUseCase;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.http.LayerCached;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FWI of an AOI (contract getAoiFwi), also as text/csv. The aoiId path variable is checked by CatalogApi.requireAoi
 * (400 malformed, 404 unknown) so the record's field errors keep their own param names.
 */
@RestController
@RequestMapping(path = "/api/v1/aois/{aoiId}")
class FwiController {

    private final GetAoiFwiUseCase fwi;

    FwiController(GetAoiFwiUseCase fwi) {
        this.fwi = fwi;
    }

    @LayerCached
    @GetMapping(path = "/fwi", produces = {"application/json", "text/csv"})
    FwiResponse fwi(ReleaseRef release, @PathVariable("aoiId") String aoiId, @Valid @ParameterObject FwiRequest request) {
        return fwi.fwi(release, aoiId, request.toQuery());
    }
}
