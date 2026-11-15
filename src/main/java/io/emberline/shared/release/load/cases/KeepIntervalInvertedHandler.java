package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;

/** v1.2: value_lo above value_hi (finite bounds) — kept as stored (A1); every response and CSV serves the stored band. */
public final class KeepIntervalInvertedHandler implements DataCaseHandler {

    @Override
    public DataCase handles() {
        return DataCase.INTERVAL_INVERTED;
    }

    @Override
    public void handle(String file, long rows, ValidationReport report) {
        report.warn("INTERVAL_INVERTED", file, rows + " rows kept as stored");
    }
}
