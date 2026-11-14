package io.emberline.provenance.adapter.in.web;

import io.emberline.provenance.adapter.in.web.dto.SatelliteStatusRequest;
import io.emberline.provenance.application.port.in.GetSatelliteStatusUseCase;
import io.emberline.provenance.application.port.in.SatelliteStatusResponse;
import io.emberline.shared.release.ReleaseRef;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
class SatelliteStatusController {

    private final GetSatelliteStatusUseCase useCase;

    SatelliteStatusController(GetSatelliteStatusUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/satellite-status")
    SatelliteStatusResponse status(ReleaseRef release, @Valid @ParameterObject SatelliteStatusRequest request) {
        return useCase.status(release, request.toQuery());
    }
}
