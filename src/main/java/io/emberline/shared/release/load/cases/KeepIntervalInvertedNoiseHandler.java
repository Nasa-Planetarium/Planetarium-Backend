package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;

/**
 * v1.2: value_lo above value_hi by at most {@link DataCase#INTERVAL_NOISE_TOLERANCE} — float noise, kept as stored (A1);
 * the read SELECTs serve the band ordered so it is never inverted. Larger inversions stay {@code INTERVAL_INVERTED}.
 */
public final class KeepIntervalInvertedNoiseHandler implements DataCaseHandler {

    @Override
    public DataCase handles() {
        return DataCase.INTERVAL_INVERTED_NOISE;
    }

    @Override
    public void handle(String file, long rows, ValidationReport report) {
        report.warn("INTERVAL_INVERTED_NOISE", file,
                rows + " rows kept as stored (lo-hi <= " + DataCase.INTERVAL_NOISE_TOLERANCE_TEXT + ")");
    }
}
