package io.emberline.fireinfo.adapter.in.web;

import io.emberline.fireinfo.adapter.in.web.dto.SpreadNowcastRequest;
import io.emberline.fireinfo.application.port.in.GetSpreadNowcastUseCase;
import io.emberline.fireinfo.application.port.in.SpreadNowcastResponse;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.http.LayerCached;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spread nowcast cells (contract getSpreadNowcast). JSON only — the contract lists no text/csv for it (format=csv → 406). */
@RestController
@RequestMapping(path = "/api/v1")
class SpreadNowcastController {

    private final GetSpreadNowcastUseCase nowcast;

    SpreadNowcastController(GetSpreadNowcastUseCase nowcast) {
        this.nowcast = nowcast;
    }

    @LayerCached
    @GetMapping(path = "/spread-nowcast", produces = "application/json")
    SpreadNowcastResponse nowcast(ReleaseRef release, @Valid @ParameterObject SpreadNowcastRequest request) {
        return nowcast.nowcast(release, request.toQuery());
    }
}
