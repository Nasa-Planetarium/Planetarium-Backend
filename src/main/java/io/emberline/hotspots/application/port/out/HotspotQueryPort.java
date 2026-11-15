package io.emberline.hotspots.application.port.out;

import io.emberline.hotspots.domain.Hotspot;
import io.emberline.hotspots.domain.HotspotScan;
import io.emberline.hotspots.domain.Layer;
import io.emberline.hotspots.domain.PagePosition;
import io.emberline.hotspots.domain.Totals;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** Read side of {@code <release root>/<release_key>/harmonized_hotspots/{reference,other}/year=YYYY/month=MM/*.parquet}. */
public interface HotspotQueryPort {

    /**
     * Parquet files of the layer's year/month folders from {@code from} to {@code to} (folders that do not exist are
     * skipped); empty when the release has no harmonized_hotspots folder (or not the layer's sub-folders).
     */
    Optional<List<Path>> files(String releaseKey, Layer layer, YearMonth from, YearMonth to);

    /**
     * Existing {@code harmonized_hotspots_static_flags/year=YYYY.parquet} files for {@code fromYear..toYear} (the flag
     * year is the date_local year); empty when the release has no flag folder.
     */
    List<Path> staticFlagFiles(String releaseKey, int fromYear, int toYear);

    /** Count of every matching point and the reference sum over them (regardless of any page). */
    Totals totals(HotspotScan scan);

    /** Up to {@code n} matching points in the public order, starting at {@code after} (or the first point). */
    List<Hotspot> page(HotspotScan scan, Optional<PagePosition> after, int n);
}
