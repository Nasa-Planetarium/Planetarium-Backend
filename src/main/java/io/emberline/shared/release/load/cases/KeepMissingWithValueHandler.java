package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;

/** A1: status missing with a value — stored as the file has it; the read side shows status missing. */
public final class KeepMissingWithValueHandler implements DataCaseHandler {

    @Override
    public DataCase handles() {
        return DataCase.MISSING_MONTH_WITH_VALUE;
    }

    @Override
    public void handle(String file, long rows, ValidationReport report) {
        report.warn("MISSING_MONTH_WITH_VALUE", file, rows + " rows kept as-is");
    }
}
