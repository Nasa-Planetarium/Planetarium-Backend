package io.emberline.observation.application.service;

import io.emberline.observation.application.port.in.CellCollection.CellProperties;
import io.emberline.observation.domain.CellCase;
import io.emberline.observation.domain.CellRow;
import org.springframework.stereotype.Component;

/** E2: value null (missing month) — served as null with status missing, never as 0; no interval either. */
@Component
class MissingValueHandler implements CellCaseHandler {

    @Override
    public CellCase handles() {
        return CellCase.VALUE_MISSING;
    }

    @Override
    public boolean applies(CellRow row) {
        return row.value() == null;
    }

    @Override
    public CellProperties properties(CellRow row) {
        return CellCaseHandler.of(row, false, true);
    }
}
