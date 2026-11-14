package io.emberline.provenance.adapter.in.web;

import io.emberline.provenance.application.port.in.GetVersionUseCase;
import io.emberline.provenance.application.port.in.VersionResponse;
import io.emberline.shared.release.PinnedRelease;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
class VersionController {

    private final GetVersionUseCase useCase;

    VersionController(GetVersionUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/version")
    VersionResponse version(PinnedRelease release) {
        return useCase.version(release);
    }
}
