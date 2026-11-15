package io.emberline.observation.adapter.in.web;

import io.emberline.observation.adapter.in.web.dto.CellFrpRequest;
import io.emberline.observation.application.port.in.CellFrpList;
import io.emberline.observation.application.port.in.ListCellFrpUseCase;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.http.LayerCached;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FRP of 0.1° cells (v1.2 layer): JSON, or CSV ({@code Accept: text/csv} or {@code format=csv}) via {@link FrpCsvConverter}. */
@RestController
@RequestMapping(path = "/api/v1")
class FrpController {

    private final ListCellFrpUseCase listCellFrp;

    FrpController(ListCellFrpUseCase listCellFrp) {
        this.listCellFrp = listCellFrp;
    }

    @LayerCached
    @GetMapping(path = "/cells/frp", produces = {"application/json", "text/csv"})
    CellFrpList frp(ReleaseRef release, @Valid @ParameterObject CellFrpRequest request) {
        return listCellFrp.frp(release, request.toQuery());
    }
}
