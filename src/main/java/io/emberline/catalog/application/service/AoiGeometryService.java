package io.emberline.catalog.application.service;

import io.emberline.catalog.api.AoiGeometry;
import io.emberline.catalog.api.AoiGeometryApi;
import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.catalog.application.port.out.AoiReadPort;
import io.emberline.catalog.domain.Aoi;
import io.emberline.catalog.domain.Bbox;
import io.emberline.shared.release.ReleaseRef;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Serves boundaries (bbox from the in-memory catalog, geometry JSON from catalog.aoi_boundary) to other contexts. */
@Service
class AoiGeometryService implements AoiGeometryApi {

    private final CatalogApi catalog;
    private final AoiDirectory directory;
    private final AoiReadPort port;

    AoiGeometryService(CatalogApi catalog, AoiDirectory directory, AoiReadPort port) {
        this.catalog = catalog;
        this.directory = directory;
        this.port = port;
    }

    @Override
    public AoiGeometry requireGeometry(ReleaseRef release, String aoiId, String param) {
        AoiSummary summary = catalog.requireAoi(release, aoiId, param);
        Aoi a = directory.of(release.releaseId()).byId().get(summary.aoiId());
        Bbox b = a.bbox();
        String geometry = b == null ? null : port.boundaryGeometry(release.releaseId(), a.aoiId()).orElse(null);
        return b == null ? new AoiGeometry(a.aoiId(), a.level().code(), null, null, null, null, null)
                : new AoiGeometry(a.aoiId(), a.level().code(), b.west(), b.south(), b.east(), b.north(), geometry);
    }

    @Override
    public List<AoiSummary> aoisAtLevels(ReleaseRef release, Set<String> levels) {
        return directory.of(release.releaseId()).ordered().stream()
                .filter(a -> levels.contains(a.level().code()))
                .map(a -> catalog.findAoi(release, a.aoiId()).orElseThrow())
                .toList();
    }
}
