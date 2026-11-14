package io.emberline.observation.application.service;

import io.emberline.observation.application.port.in.CellCollection.CellProperties;
import io.emberline.observation.domain.CellCase;
import io.emberline.observation.domain.CellRow;
import org.springframework.stereotype.Component;

/** value inside its interval: no flag. */
@Component
class RegularCellHandler implements CellCaseHandler {

    @Override
    public CellCase handles() {
        return CellCase.REGULAR;
    }

    @Override
    public boolean applies(CellRow row) {
        return true;
    }

    @Override
    public CellProperties properties(CellRow row) {
        return CellCaseHandler.of(row, false, false);
    }
}
