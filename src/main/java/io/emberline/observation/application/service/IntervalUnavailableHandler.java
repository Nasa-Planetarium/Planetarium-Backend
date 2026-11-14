package io.emberline.observation.application.service;

import io.emberline.observation.application.port.in.CellCollection.CellProperties;
import io.emberline.observation.domain.CellCase;
import io.emberline.observation.domain.CellRow;
import org.springframework.stereotype.Component;

/** value present, value_lo / value_hi null (Terra-only months, meta cell_interval): interval_unavailable. */
@Component
class IntervalUnavailableHandler implements CellCaseHandler {

    @Override
    public CellCase handles() {
        return CellCase.INTERVAL_UNAVAILABLE;
    }

    @Override
    public boolean applies(CellRow row) {
        return !row.intervalPresent();
    }

    @Override
    public CellProperties properties(CellRow row) {
        return CellCaseHandler.of(row, false, true);
    }
}
