package io.emberline.fireinfo.application.service;

import io.emberline.catalog.api.CatalogApi;
import io.emberline.fireinfo.application.port.in.FireRegimeMonthItem;
import io.emberline.fireinfo.application.port.in.FireRegimeQuery;
import io.emberline.fireinfo.application.port.in.FireRegimeResponse;
import io.emberline.fireinfo.application.port.in.GetAoiFireRegimeUseCase;
import io.emberline.fireinfo.application.port.out.FireInfoReadPort;
import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.fireinfo.domain.RegimeHead;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.json.Coverage;
import io.emberline.shared.release.ReleaseRef;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Fire regime badge months of an AOI (contract {@code getAoiFireRegime}): the AOI's rows of aoi_month_fire_regime in
 * {@code start}..{@code end} (YYYY-MM, both optional = the whole file), values as stored, {@code top_event_id} passed
 * through (also on {@code none} months). Range = the AOIs the file carries; others → 200, {@code months: []},
 * {@code coverage.available=false}.
 */
@Service
class FireRegimeService implements GetAoiFireRegimeUseCase {

    static final int FIRST = 0;
    static final int LAST = 999_912;

    private final FireInfoReadPort port;
    private final CatalogApi catalog;
    private final LayerSupport layers;

    FireRegimeService(FireInfoReadPort port, CatalogApi catalog, LayerSupport layers) {
        this.port = port;
        this.catalog = catalog;
        this.layers = layers;
    }

    @Override
    public FireRegimeResponse regime(ReleaseRef release, String aoiId, FireRegimeQuery query) {
        YearMonth start = Params.given(query.start()) ? Params.yearMonth(query.start(), "start") : null;
        YearMonth end = Params.given(query.end()) ? Params.yearMonth(query.end(), "end") : null;
        if (start != null && end != null && start.isAfter(end)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "end", "end must not be before start");
        }
        catalog.requireAoi(release, aoiId, "aoi_id");
        int from = start == null ? FIRST : key(start);
        int to = end == null ? LAST : key(end);
        String normalized = Params.normalized("aoi_id", aoiId, "start", start, "end", end);
        return layers.cached(release, LayerRoute.FIRE_REGIME, normalized, FireRegimeResponse.class, () -> {
            layers.require(release, LayerFile.FIRE_REGIME);
            RegimeHead head = port.regimeHead(release.releaseId(), aoiId);
            if (!head.present()) {
                return new FireRegimeResponse(aoiId, null, List.of(),
                        Coverage.unavailable(Coverage.Scope.KR_ARG_REGIME, Coverage.Reason.OUT_OF_COVERAGE),
                        release.dataRelease());
            }
            List<FireRegimeMonthItem> months = port.regimeMonths(release.releaseId(), aoiId, from, to).stream()
                    .map(FireRegimeMonthItem::of).toList();
            return new FireRegimeResponse(aoiId, head.name(), months, Coverage.availableFor(Coverage.Scope.KR_ARG_REGIME),
                    release.dataRelease());
        });
    }

    private static int key(YearMonth ym) {
        return ym.getYear() * 100 + ym.getMonthValue();
    }
}
