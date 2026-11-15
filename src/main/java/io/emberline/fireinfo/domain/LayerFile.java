package io.emberline.fireinfo.domain;

import java.util.Arrays;
import java.util.Optional;

/** The release files of the fire-info layers. Each is optional in a release; a route needs its file (else 503 50502). */
public enum LayerFile {
    FIRE_EVENTS("fire_events.parquet"),
    FWI_AOI_DAY("fwi_aoi_day.parquet"),
    FWI_CLIM_AOI_MONTH("fwi_clim_aoi_month.parquet"),
    SPREAD_NOWCAST("spread_nowcast_latest.parquet"),
    FIRE_REGIME("aoi_month_fire_regime.parquet");

    private final String fileName;

    LayerFile(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }

    public static Optional<LayerFile> ofFileName(String fileName) {
        return Arrays.stream(values()).filter(f -> f.fileName.equals(fileName)).findFirst();
    }
}
