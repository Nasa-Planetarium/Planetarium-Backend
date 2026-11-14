package io.emberline.observation.application.service;

import io.emberline.catalog.api.AoiGeometryApi;
import io.emberline.catalog.api.AoiSummary;
import io.emberline.ingest.api.ReleaseActivated;
import io.emberline.shared.event.EventEnvelope;
import io.emberline.shared.event.StreamConsumer;
import io.emberline.shared.release.ReleaseRef;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * cache-warmer (consumer group: one pod per activation, once per eventId through the inbox): prepares the L1 cell
 * areas of the activated release for the bundle countries + Korean sido / sgg, so the first /cells?aoi_id=… after a
 * switch does not parse boundaries. Counts warmed AOIs in emberline_cache_warm_total.
 */
@Component
@StreamConsumer("cache-warmer")
class CacheWarmer implements Consumer<EventEnvelope<ReleaseActivated>> {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);
    static final Set<String> LEVELS = Set.of("country", "kr_sido", "kr_sgg");

    private final AoiGeometryApi geometries;
    private final AoiCellAreas areas;
    private final Counter warmed;

    CacheWarmer(AoiGeometryApi geometries, AoiCellAreas areas, MeterRegistry meters) {
        this.geometries = geometries;
        this.areas = areas;
        this.warmed = Counter.builder("emberline.cache.warm").description("AOIs warmed after release.activated").register(meters);
    }

    @Override
    public void accept(EventEnvelope<ReleaseActivated> envelope) {
        ReleaseActivated e = envelope.payload();
        ReleaseRef release = new ReleaseRef(e.releaseId(), e.releaseKey(), e.methodVersion(), e.dataVersion(), null);
        List<AoiSummary> aois = geometries.aoisAtLevels(release, LEVELS);
        for (AoiSummary a : aois) {
            areas.of(release.releaseId(), geometries.requireGeometry(release, a.aoiId(), "aoi_id"));
        }
        warmed.increment(aois.size());
        log.info("cache-warmer warmed {} AOIs of release {}", aois.size(), e.releaseKey());
    }
}
