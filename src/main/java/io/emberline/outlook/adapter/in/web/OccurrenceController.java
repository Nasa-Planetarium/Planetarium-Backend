package io.emberline.outlook.adapter.in.web;

import io.emberline.outlook.adapter.in.web.dto.AoiOccurrenceListRequest;
import io.emberline.outlook.adapter.in.web.dto.GridOccurrenceRequest;
import io.emberline.outlook.application.port.in.GetAoiOccurrenceUseCase;
import io.emberline.outlook.application.port.in.GetGridOccurrenceUseCase;
import io.emberline.outlook.application.port.in.LayerBody;
import io.emberline.outlook.application.port.in.ListAoiOccurrenceUseCase;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.http.LayerCached;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Next-month occurrence (tag outlook). JSON, or CSV ({@code Accept: text/csv} or {@code format=csv}) on the two
 * routes with {@code format}, written by {@link LayerCsvConverter}. Layer cache policy ({@link LayerCached}): browsers
 * revalidate, shared caches hold 3600 s; errors are no-store. The hand-off alias {@code /api/occurrence} forwards here.
 */
@RestController
@RequestMapping(path = "/api/v1")
class OccurrenceController {

    private final GetGridOccurrenceUseCase grid;
    private final ListAoiOccurrenceUseCase list;
    private final GetAoiOccurrenceUseCase aoi;

    OccurrenceController(GetGridOccurrenceUseCase grid, ListAoiOccurrenceUseCase list, GetAoiOccurrenceUseCase aoi) {
        this.grid = grid;
        this.list = list;
        this.aoi = aoi;
    }

    @LayerCached
    @GetMapping(path = "/occurrence/grid", produces = {"application/json", "text/csv"})
    LayerBody grid(ReleaseRef release, @Valid @ParameterObject GridOccurrenceRequest request) {
        return grid.grid(release, request.toQuery());
    }

    @LayerCached
    @GetMapping(path = "/occurrence/aois", produces = {"application/json", "text/csv"})
    LayerBody aois(ReleaseRef release, @Valid @ParameterObject AoiOccurrenceListRequest request) {
        return list.list(release, request.parentId());
    }

    @LayerCached
    @GetMapping(path = "/aois/{aoiId}/occurrence", produces = "application/json")
    LayerBody aoi(ReleaseRef release, @PathVariable("aoiId") String aoiId) {
        return aoi.aoi(release, aoiId);
    }
}
