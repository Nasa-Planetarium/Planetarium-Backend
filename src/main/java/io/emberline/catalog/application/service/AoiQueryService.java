package io.emberline.catalog.application.service;

import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.catalog.application.port.in.AoiDetailResponse;
import io.emberline.catalog.application.port.in.AoiItem;
import io.emberline.catalog.application.port.in.AoiListQuery;
import io.emberline.catalog.application.port.in.AoiListResponse;
import io.emberline.catalog.application.port.in.BoundaryFeature;
import io.emberline.catalog.application.port.in.GetAoiUseCase;
import io.emberline.catalog.application.port.in.ListAoisUseCase;
import io.emberline.catalog.application.port.out.AoiReadPort;
import io.emberline.catalog.domain.Aoi;
import io.emberline.shared.Units;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.error.ResourceNotFoundException;
import io.emberline.shared.json.RawJson;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.validation.Patterns;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Lists, finds and serves AOIs of the pinned release from the in-memory catalog; boundaries from the database. */
@Service
class AoiQueryService implements ListAoisUseCase, GetAoiUseCase, CatalogApi {

    private static final Pattern AOI_ID = Pattern.compile(Patterns.AOI_ID);

    private final AoiDirectory directory;
    private final AoiReadPort port;

    AoiQueryService(AoiDirectory directory, AoiReadPort port) {
        this.directory = directory;
        this.port = port;
    }

    @Override
    public AoiListResponse list(ReleaseRef release, AoiListQuery query) {
        AoiDirectory.Snapshot snapshot = directory.of(release.releaseId());
        if (query.parentId() != null) {
            require(snapshot, query.parentId(), "parent_id");
        }
        String after = query.cursor() == null ? null : AoiCursor.decode(query.cursor(), release.dataVersion());
        String q = query.q() == null ? "" : query.q().strip();
        List<AoiItem> page = new ArrayList<>();
        boolean skipping = after != null;
        String next = null;
        for (Aoi a : snapshot.ordered()) {
            if (skipping) {
                skipping = !a.aoiId().equals(after);
                continue;
            }
            if (!a.hidden() || query.includeHidden()) {
                if ((query.level() == null || a.level().code().equals(query.level()))
                        && (query.parentId() == null || query.parentId().equals(a.parentId()))
                        && (q.isEmpty() || a.matches(q))) {
                    if (page.size() == query.limit()) {
                        next = AoiCursor.encode(release.dataVersion(), page.get(page.size() - 1).aoiId());
                        break;
                    }
                    page.add(item(a));
                }
            }
        }
        if (skipping) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "cursor", "cursor does not point into this list");
        }
        return new AoiListResponse(release.methodVersion(), Units.UNIT, page.size(), next, page);
    }

    @Override
    public AoiDetailResponse get(ReleaseRef release, String aoiId) {
        Aoi a = require(directory.of(release.releaseId()), aoiId, "aoi_id");
        return new AoiDetailResponse(a.aoiId(), a.nameKo(), a.nameEn(), a.level().code(), a.parentId(), a.nCells(),
                a.hidden(), a.hasData(), a.firstMonth(), a.lastMonth(), a.bbox() == null ? null : a.bbox().rounded(),
                release.methodVersion());
    }

    @Override
    public BoundaryFeature boundary(ReleaseRef release, String aoiId) {
        Aoi a = require(directory.of(release.releaseId()), aoiId, "aoi_id");
        RawJson geometry = port.boundaryGeometry(release.releaseId(), a.aoiId()).map(RawJson::new).orElse(null);
        return new BoundaryFeature("Feature", geometry, new BoundaryFeature.Properties(a.aoiId(), release.methodVersion()));
    }

    @Override
    public AoiSummary requireAoi(ReleaseRef release, String aoiId, String param) {
        return summary(require(directory.of(release.releaseId()), aoiId, param));
    }

    @Override
    public Optional<AoiSummary> findAoi(ReleaseRef release, String aoiId) {
        return Optional.ofNullable(aoiId).map(id -> directory.of(release.releaseId()).byId().get(id)).map(AoiQueryService::summary);
    }

    private static Aoi require(AoiDirectory.Snapshot snapshot, String aoiId, String param) {
        if (aoiId == null || !AOI_ID.matcher(aoiId).matches()) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " must match " + Patterns.AOI_ID);
        }
        Aoi a = snapshot.byId().get(aoiId);
        if (a == null) {
            throw new ResourceNotFoundException(ErrorCode.UNKNOWN_AOI, param, param + " " + aoiId + " is not in aoi_list");
        }
        return a;
    }

    private static AoiItem item(Aoi a) {
        return new AoiItem(a.aoiId(), a.nameKo(), a.nameEn(), a.level().code(), a.parentId(), a.nCells(), a.hidden(),
                a.hasData(), a.firstMonth(), a.lastMonth(), a.bbox() == null ? null : a.bbox().rounded());
    }

    private static AoiSummary summary(Aoi a) {
        return new AoiSummary(a.aoiId(), a.nameKo(), a.nameEn(), a.level().code(), a.parentId(), a.nCells(), a.hidden());
    }
}
