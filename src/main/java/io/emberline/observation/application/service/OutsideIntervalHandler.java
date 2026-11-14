package io.emberline.observation.application.service;

import io.emberline.observation.application.port.in.CellCollection.CellProperties;
import io.emberline.observation.domain.CellCase;
import io.emberline.observation.domain.CellRow;
import org.springframework.stereotype.Component;

/** E3: value outside [value_lo, value_hi] — values kept as the file has them, the interval is flagged as not usable. */
@Component
class OutsideIntervalHandler implements CellCaseHandler {

    @Override
    public CellCase handles() {
        return CellCase.VALUE_OUTSIDE_INTERVAL;
    }

    @Override
    public boolean applies(CellRow row) {
        return row.value() < row.valueLo() || row.value() > row.valueHi();
    }

    @Override
    public CellProperties properties(CellRow row) {
        return CellCaseHandler.of(row, true, false);
    }
}
