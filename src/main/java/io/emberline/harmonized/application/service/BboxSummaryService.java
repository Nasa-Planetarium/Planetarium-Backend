package io.emberline.harmonized.application.service;

import io.emberline.harmonized.application.port.in.BboxSummaryQuery;
import io.emberline.harmonized.application.port.in.GetBboxSummaryUseCase;
import io.emberline.harmonized.application.port.in.SummaryResponse;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import org.springframework.stereotype.Service;

/**
 * bbox summary (openapi getBboxSummary, {@code x-status: release-absent} = 503 with this release). The parameters are
 * checked as on the AOI summary first (400 before 503), then the route answers 503 data_not_loaded like every
 * release-absent read.
 */
@Service
class BboxSummaryService implements GetBboxSummaryUseCase {

    static final String NOT_SERVED = "bbox sums are not served by this release; use /api/v1/aois/{aoiId}/summary or /api/v1/cells";

    private final EmberlineProperties.Limits limits;

    BboxSummaryService(EmberlineProperties properties) {
        this.limits = properties.limits();
    }

    @Override
    public SummaryResponse summary(BboxSummaryQuery query) {
        checkBbox(query.bbox());
        SummaryService.check(query.range(), limits);
        throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, NOT_SERVED);
    }

    /** Same rule as /hotspots: four finite numbers, west < east, south < north, longitudes ±180, latitudes ±90. */
    static void checkBbox(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            throw badBbox();
        }
        double[] v = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                v[i] = Double.parseDouble(parts[i].strip());
                if (!Double.isFinite(v[i])) {
                    throw badBbox();
                }
            }
        } catch (NumberFormatException e) {
            throw badBbox();
        }
        if (v[0] < -180 || v[2] > 180 || v[1] < -90 || v[3] > 90 || v[0] >= v[2] || v[1] >= v[3]) {
            throw badBbox();
        }
    }

    private static InvalidRequestException badBbox() {
        return new InvalidRequestException(ErrorCode.INVALID_PARAM, "bbox",
                "bbox must be west,south,east,north with west < east, south < north, longitudes within ±180 and latitudes within ±90");
    }
}
