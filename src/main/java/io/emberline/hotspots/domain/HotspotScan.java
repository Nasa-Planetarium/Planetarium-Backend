package io.emberline.hotspots.domain;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What to read: the parquet files of the touched year/month folders, the date_local range, the bbox (pushed into
 * the SQL) and an optional point filter applied after it.
 */
public record HotspotScan(List<Path> files, LocalDate start, LocalDate end, BboxArea bbox, Optional<PointFilter> filter) {

    public HotspotScan {
        files = List.copyOf(files);
    }
}
