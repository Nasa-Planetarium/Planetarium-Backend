package io.emberline.provenance.adapter.in.web;

import io.emberline.provenance.application.port.in.GetMethodUseCase;
import io.emberline.provenance.application.port.in.MethodResponse;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.validation.Patterns;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
class MethodController {

    private final GetMethodUseCase useCase;

    MethodController(GetMethodUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/methods/current")
    MethodResponse current(ReleaseRef release) {
        return useCase.current(release);
    }

    @GetMapping("/methods/{methodVersion}")
    MethodResponse byVersion(ReleaseRef release,
                             @PathVariable("methodVersion") @Pattern(regexp = Patterns.METHOD_VERSION) String methodVersion) {
        return useCase.byVersion(release, methodVersion);
    }
}
