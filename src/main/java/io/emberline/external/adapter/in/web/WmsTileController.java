package io.emberline.external.adapter.in.web;

import io.emberline.external.adapter.in.web.dto.WmsTileRequest;
import io.emberline.external.application.port.in.GetWmsTileUseCase;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1")
class WmsTileController {

    private final GetWmsTileUseCase tiles;

    WmsTileController(GetWmsTileUseCase tiles) {
        this.tiles = tiles;
    }

    @GetMapping(path = "/firms/wms-tiles", produces = "image/png")
    byte[] tile(@Valid @ParameterObject WmsTileRequest request) {
        return tiles.tile(request.toQuery());
    }
}
