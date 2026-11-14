package io.emberline.hotspots.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code emberline.hotspots.*}: points are served only when zoomed in — bbox (or AOI boundary bbox) area ≤
 * {@code maxAreaDeg2} and start..end ≤ {@code maxDays} days; wider requests are sent to the cell layer (/cells).
 * DuckDB reader: {@code duckdbThreads} and {@code duckdbMemoryLimit} per process.
 */
@ConfigurationProperties("emberline.hotspots")
public record HotspotSettings(@DefaultValue("100") double maxAreaDeg2, @DefaultValue("366") int maxDays,
                              @DefaultValue("4") int duckdbThreads, @DefaultValue("512MB") String duckdbMemoryLimit) {

    public HotspotSettings {
        if (!(maxAreaDeg2 > 0) || maxDays <= 0) {
            throw new IllegalArgumentException("emberline.hotspots.max-area-deg2 and max-days must be positive");
        }
        duckdbThreads = Math.max(1, Math.min(duckdbThreads, 64));
        duckdbMemoryLimit = duckdbMemoryLimit == null || !duckdbMemoryLimit.matches("^[0-9]+(\\.[0-9]+)?\\s?(MB|GB|MiB|GiB)$")
                ? "512MB" : duckdbMemoryLimit;
    }
}
