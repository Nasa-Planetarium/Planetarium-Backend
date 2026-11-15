package io.emberline.hotspots.domain;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What to read: the parquet files of the touched year/month folders, the date_local range, the bbox (pushed into
 * the SQL), an optional point filter applied after it and the static-source flag files (v1.2
 * harmonized_hotspots_static_flags/year=YYYY.parquet) whose {@code is_static_source} points are left out — empty
 * when the release has no flag folder.
 */
public record HotspotScan(List<Path> files, LocalDate start, LocalDate end, BboxArea bbox, Optional<PointFilter> filter,
                          List<Path> staticFlags) {

    public HotspotScan {
        files = List.copyOf(files);
        staticFlags = List.copyOf(staticFlags);
    }

    public HotspotScan(List<Path> files, LocalDate start, LocalDate end, BboxArea bbox, Optional<PointFilter> filter) {
        this(files, start, end, bbox, filter, List.of());
    }
}
