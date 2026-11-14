package io.emberline.observation.application.service;

import io.emberline.observation.application.port.in.HotspotList;
import io.emberline.observation.application.port.in.HotspotsQuery;
import io.emberline.observation.application.port.in.ListHotspotsUseCase;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import org.springframework.stereotype.Service;

/**
 * GET /api/v1/hotspots. No release ships harmonized_hotspots yet (00-decisions E5, owner ruling: 「준비 중」), so the
 * route answers 503 data_not_loaded (Retry-After) after validating the query; other routes are unaffected.
 */
@Service
class HotspotQueryService implements ListHotspotsUseCase {

    static final String PREPARING = "harmonized_hotspots is not provided by this release yet (준비 중); "
            + "use /api/v1/cells for the 0.1° map";

    @Override
    public HotspotList hotspots(ReleaseRef release, HotspotsQuery query) {
        throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, PREPARING);
    }
}
