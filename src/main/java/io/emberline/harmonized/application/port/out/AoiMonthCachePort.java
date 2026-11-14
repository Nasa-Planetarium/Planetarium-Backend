package io.emberline.harmonized.application.port.out;

import io.emberline.harmonized.domain.AoiMonths;
import io.emberline.shared.release.ReleaseRef;
import java.util.Optional;

/**
 * The per-AOI bundle in redis-cache (key carries method_version and data_version, so a release switch never reads an
 * old entry). Failures are swallowed: empty on get, no-op on put — the caller then answers from the database.
 */
public interface AoiMonthCachePort {

    Optional<AoiMonths> get(ReleaseRef release, String aoiId, String route);

    void put(ReleaseRef release, AoiMonths months, String route);
}
