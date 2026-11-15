package io.emberline.fireinfo.domain;

import java.time.LocalDate;

/** One AOI-day of fwi_aoi_day.parquet, served columns as stored (date = NASA POWER LST day). */
public record FwiDay(LocalDate date, Double fwiMean, Double fwiMax, String fwiClass, Double fwiPct, String fwiRelClass,
                     Double isiMean, Double buiMean, Double ffmcMean, Double dcMean, Integer nCells) {
}
