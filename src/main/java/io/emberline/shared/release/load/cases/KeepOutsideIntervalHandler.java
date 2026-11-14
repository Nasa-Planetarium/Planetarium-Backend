package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;

/** E3: value outside [value_lo, value_hi] — the value is the model's and stays; the row's interval is flagged on read. */
public final class KeepOutsideIntervalHandler implements DataCaseHandler {

    @Override
    public DataCase handles() {
        return DataCase.VALUE_OUTSIDE_INTERVAL;
    }

    @Override
    public void handle(String file, long rows, ValidationReport report) {
        report.warn("VALUE_OUTSIDE_INTERVAL", file, rows + " rows kept; interval not used for them");
    }
}
