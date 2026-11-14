package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;

/** E2: a missing month keeps value NULL (status missing); it is never turned into 0. */
public final class KeepNullAsMissingHandler implements DataCaseHandler {

    @Override
    public DataCase handles() {
        return DataCase.MISSING_MONTH_NULL_VALUE;
    }

    @Override
    public void handle(String file, long rows, ValidationReport report) {
        report.warn("MISSING_MONTH_NULL_VALUE", file, rows + " rows kept NULL (status missing)");
    }
}
