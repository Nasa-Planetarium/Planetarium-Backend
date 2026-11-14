package io.emberline.observation.adapter.in.web;

import io.emberline.observation.adapter.in.web.dto.CellsRequest;
import io.emberline.observation.application.port.in.CellCollection;
import io.emberline.observation.application.port.in.ListCellsUseCase;
import io.emberline.shared.json.GeoJson;
import io.emberline.shared.release.ReleaseRef;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1")
class CellController {

    private final ListCellsUseCase listCells;

    CellController(ListCellsUseCase listCells) {
        this.listCells = listCells;
    }

    @GetMapping(path = "/cells", produces = {GeoJson.MEDIA_TYPE, "application/json"})
    CellCollection cells(ReleaseRef release, @Valid @ParameterObject CellsRequest request) {
        return listCells.cells(release, request.toQuery());
    }
}
