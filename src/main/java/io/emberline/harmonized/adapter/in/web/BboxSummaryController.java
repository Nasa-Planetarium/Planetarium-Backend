package io.emberline.harmonized.adapter.in.web;

import io.emberline.harmonized.adapter.in.web.dto.BboxSummaryRequest;
import io.emberline.harmonized.application.port.in.GetBboxSummaryUseCase;
import io.emberline.harmonized.application.port.in.SummaryResponse;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** bbox summary (openapi getBboxSummary). No release argument: release-absent, so 503 does not depend on the release. */
@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
class BboxSummaryController {

    private final GetBboxSummaryUseCase summary;

    BboxSummaryController(GetBboxSummaryUseCase summary) {
        this.summary = summary;
    }

    @GetMapping(path = "/summaries", produces = {"application/json", "text/csv"})
    SummaryResponse summary(@Valid @ParameterObject BboxSummaryRequest request) {
        return summary.summary(request.toQuery());
    }
}
