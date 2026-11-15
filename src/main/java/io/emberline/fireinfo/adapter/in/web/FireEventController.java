package io.emberline.fireinfo.adapter.in.web;

import io.emberline.fireinfo.adapter.in.web.dto.FireEventListRequest;
import io.emberline.fireinfo.application.port.in.FireEventDetailResponse;
import io.emberline.fireinfo.application.port.in.FireEventListResponse;
import io.emberline.fireinfo.application.port.in.GetFireEventUseCase;
import io.emberline.fireinfo.application.port.in.ListFireEventsUseCase;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.http.LayerCached;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fire events (contract listFireEvents, getFireEvent). The list also as text/csv ({@code format=csv} or Accept). */
@RestController
@RequestMapping(path = "/api/v1")
class FireEventController {

    private final ListFireEventsUseCase list;
    private final GetFireEventUseCase detail;

    FireEventController(ListFireEventsUseCase list, GetFireEventUseCase detail) {
        this.list = list;
        this.detail = detail;
    }

    @LayerCached
    @GetMapping(path = "/fire-events", produces = {"application/json", "text/csv"})
    FireEventListResponse list(ReleaseRef release, @Valid @ParameterObject FireEventListRequest request) {
        return list.list(release, request.toQuery());
    }

    @LayerCached
    @GetMapping(path = "/fire-events/{eventId}", produces = "application/json")
    FireEventDetailResponse event(ReleaseRef release, @PathVariable("eventId") String eventId) {
        return detail.event(release, eventId);
    }
}
