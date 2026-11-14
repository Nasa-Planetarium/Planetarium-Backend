package io.emberline.hotspots.application.service;

import io.emberline.hotspots.api.HotspotLimits;
import io.emberline.hotspots.domain.Layer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code emberline.hotspots.*}: points are served only when zoomed in — bbox (or AOI boundary bbox) area ≤
 * {@code maxAreaDeg2} and start..end ≤ {@code maxDaysReference} days for {@code layer=reference},
 * {@code maxDaysAll} for {@code layer=all} (contract Version.limits 92 / 31; /version reads the same values);
 * wider requests are sent to the cell layer (/cells).
 * DuckDB reader: {@code duckdbThreads} and {@code duckdbMemoryLimit} per process.
 */
@ConfigurationProperties("emberline.hotspots")
public record HotspotSettings(@DefaultValue("100") double maxAreaDeg2, @DefaultValue("92") int maxDaysReference,
                              @DefaultValue("31") int maxDaysAll, @DefaultValue("4") int duckdbThreads,
                              @DefaultValue("512MB") String duckdbMemoryLimit) implements HotspotLimits {

    public HotspotSettings {
        if (!(maxAreaDeg2 > 0) || maxDaysReference <= 0 || maxDaysAll <= 0) {
            throw new IllegalArgumentException("emberline.hotspots.max-area-deg2, max-days-reference and max-days-all must be positive");
        }
        duckdbThreads = Math.max(1, Math.min(duckdbThreads, 64));
        duckdbMemoryLimit = duckdbMemoryLimit == null || !duckdbMemoryLimit.matches("^[0-9]+(\\.[0-9]+)?\\s?(MB|GB|MiB|GiB)$")
                ? "512MB" : duckdbMemoryLimit;
    }

    /** The span limit of a layer. */
    public int maxDays(Layer layer) {
        return layer == Layer.ALL ? maxDaysAll : maxDaysReference;
    }
}
