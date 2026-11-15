package io.emberline.fireinfo.domain;

import java.time.LocalDate;

/** The head columns of spread_nowcast_latest.parquet (one value per file; dates are local dates). */
public record SpreadHead(LocalDate asOfDate, LocalDate targetDate, String model, String sourceSatellite,
                         boolean weatherAvailable, Integer weatherLagDays) {
}
