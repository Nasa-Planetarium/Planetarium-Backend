package io.emberline.fireinfo.application.service;

import io.emberline.catalog.api.CatalogApi;
import io.emberline.fireinfo.application.port.in.FwiDayItem;
import io.emberline.fireinfo.application.port.in.FwiQuery;
import io.emberline.fireinfo.application.port.in.FwiResponse;
import io.emberline.fireinfo.application.port.in.GetAoiFwiUseCase;
import io.emberline.fireinfo.application.port.out.FireInfoReadPort;
import io.emberline.fireinfo.domain.FireInfoTexts;
import io.emberline.fireinfo.domain.FwiHead;
import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.json.Coverage;
import io.emberline.shared.release.ReleaseRef;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * FWI of an AOI (contract {@code getAoiFwi}): {@code as_of_date} (the AOI's last day in fwi_aoi_day) in every answer;
 * no {@code date} → that day; {@code date > as_of_date} → {@code day: null} (not an error); {@code start}..{@code end}
 * → the daily {@code series} (≤ {@code emberline.limits.fwi-series-days}); {@code date} with a range → 400 40004.
 * An AOI the file does not carry → 200 with {@code coverage.available=false} and the file's last day as as_of_date.
 */
@Service
class FwiService implements GetAoiFwiUseCase {

    private final FireInfoReadPort port;
    private final CatalogApi catalog;
    private final LayerSupport layers;
    private final EmberlineProperties.Limits limits;

    FwiService(FireInfoReadPort port, CatalogApi catalog, LayerSupport layers, EmberlineProperties properties) {
        this.port = port;
        this.catalog = catalog;
        this.layers = layers;
        this.limits = properties.limits();
    }

    @Override
    public FwiResponse fwi(ReleaseRef release, String aoiId, FwiQuery query) {
        boolean hasDate = Params.given(query.date());
        boolean hasStart = Params.given(query.start());
        boolean hasEnd = Params.given(query.end());
        if (hasDate && (hasStart || hasEnd)) {
            throw new InvalidRequestException(ErrorCode.DATE_AND_RANGE, "date", "give date, or start with end — not both");
        }
        if (hasStart != hasEnd) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, hasStart ? "end" : "start", "start and end come together");
        }
        LocalDate date = hasDate ? Params.date(query.date(), "date") : null;
        LocalDate start = hasStart ? Params.date(query.start(), "start") : null;
        LocalDate end = hasEnd ? Params.date(query.end(), "end") : null;
        if (start != null) {
            if (start.isAfter(end)) {
                throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "end", "end must not be before start");
            }
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (days > limits.fwiSeriesDays()) {
                throw new InvalidRequestException(ErrorCode.SERIES_RANGE_TOO_LARGE, "end", "start..end is limited to "
                        + limits.fwiSeriesDays() + " days (requested " + days + ")");
            }
        }
        catalog.requireAoi(release, aoiId, "aoi_id");
        String normalized = Params.normalized("aoi_id", aoiId, "date", date, "start", start, "end", end);
        return layers.cached(release, LayerRoute.FWI, normalized, FwiResponse.class, () -> answer(release, aoiId, date, start, end));
    }

    private FwiResponse answer(ReleaseRef release, String aoiId, LocalDate date, LocalDate start, LocalDate end) {
        layers.require(release, LayerFile.FWI_AOI_DAY);
        String dataRelease = release.dataRelease();
        FwiHead head = port.fwiHead(release.releaseId(), aoiId);
        if (!head.present()) {
            String asOf = port.fwiLayerAsOf(release.releaseId()).map(LocalDate::toString).orElse(null);
            return new FwiResponse(aoiId, null, asOf, FireInfoTexts.FWI_BADGE, start != null ? null : date != null ? date.toString() : asOf,
                    null, List.of(), Coverage.unavailable(Coverage.Scope.KR_ARG_FWI, Coverage.Reason.OUT_OF_COVERAGE),
                    FireInfoTexts.FWI_NOTICE, dataRelease);
        }
        LocalDate asOf = head.asOfDate();
        Coverage available = Coverage.availableFor(Coverage.Scope.KR_ARG_FWI);
        if (start != null) {
            List<FwiDayItem> series = port.fwiDays(release.releaseId(), aoiId, start, end).stream().map(FwiDayItem::of).toList();
            return new FwiResponse(aoiId, head.name(), asOf.toString(), FireInfoTexts.FWI_BADGE, null, null, series, available,
                    FireInfoTexts.FWI_NOTICE, dataRelease);
        }
        LocalDate day = date != null ? date : asOf;
        FwiDayItem item = day.isAfter(asOf) ? null
                : port.fwiDays(release.releaseId(), aoiId, day, day).stream().findFirst().map(FwiDayItem::of).orElse(null);
        return new FwiResponse(aoiId, head.name(), asOf.toString(), FireInfoTexts.FWI_BADGE, day.toString(), item, List.of(),
                available, FireInfoTexts.FWI_NOTICE, dataRelease);
    }
}
