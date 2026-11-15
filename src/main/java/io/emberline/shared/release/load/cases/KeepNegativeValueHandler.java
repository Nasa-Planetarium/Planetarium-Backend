package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;

/** v1.2: value or bound below 0 — kept as stored (A1); JSON serves it as-is, anything the backend draws uses 0. */
public final class KeepNegativeValueHandler implements DataCaseHandler {

    @Override
    public DataCase handles() {
        return DataCase.NEGATIVE_VALUE;
    }

    @Override
    public void handle(String file, long rows, ValidationReport report) {
        report.warn("NEGATIVE_VALUE", file, rows + " rows kept as stored; drawn as 0");
    }
}
