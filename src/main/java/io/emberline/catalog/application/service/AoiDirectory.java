package io.emberline.catalog.application.service;

import io.emberline.catalog.application.port.out.AoiReadPort;
import io.emberline.catalog.domain.Aoi;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Pod-local (L1) catalog per release: 5,051 AOIs in list order (level, then aoi_id by code point). Keyed by
 * release_id, so a release switch needs no invalidation; only the last few releases are kept.
 */
@Component
class AoiDirectory {

    private static final int KEEP = 3;

    /** All AOIs of one release, ordered, with an id index. */
    record Snapshot(List<Aoi> ordered, Map<String, Aoi> byId) {
    }

    private final AoiReadPort port;
    private final Map<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    AoiDirectory(AoiReadPort port) {
        this.port = port;
    }

    Snapshot of(long releaseId) {
        Snapshot s = snapshots.computeIfAbsent(releaseId, this::load);
        if (snapshots.size() > KEEP) {
            snapshots.keySet().stream().sorted().limit(snapshots.size() - KEEP).toList().forEach(snapshots::remove);
        }
        return s;
    }

    private Snapshot load(long releaseId) {
        List<Aoi> ordered = port.all(releaseId).stream()
                .sorted(Comparator.comparingInt((Aoi a) -> a.level().rank()).thenComparing(Aoi::aoiId))
                .toList();
        Map<String, Aoi> byId = ordered.stream()
                .collect(Collectors.toMap(Aoi::aoiId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        return new Snapshot(ordered, byId);
    }
}
