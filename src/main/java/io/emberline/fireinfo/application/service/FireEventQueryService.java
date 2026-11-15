package io.emberline.fireinfo.application.service;

import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.fireinfo.application.port.in.FireEventDetailResponse;
import io.emberline.fireinfo.application.port.in.FireEventItem;
import io.emberline.fireinfo.application.port.in.FireEventListQuery;
import io.emberline.fireinfo.application.port.in.FireEventListResponse;
import io.emberline.fireinfo.application.port.in.GetFireEventUseCase;
import io.emberline.fireinfo.application.port.in.ListFireEventsUseCase;
import io.emberline.fireinfo.application.port.out.FireInfoReadPort;
import io.emberline.fireinfo.domain.Bbox;
import io.emberline.fireinfo.domain.EventArea;
import io.emberline.fireinfo.domain.EventPosition;
import io.emberline.fireinfo.domain.EventSelection;
import io.emberline.fireinfo.domain.EventSort;
import io.emberline.fireinfo.domain.FireEvent;
import io.emberline.fireinfo.domain.FireInfoTexts;
import io.emberline.fireinfo.domain.KrArgCoverage;
import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.error.ResourceNotFoundException;
import io.emberline.shared.json.Coverage;
import io.emberline.shared.release.ReleaseRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Fire events of the pinned release (contract {@code listFireEvents}, {@code getFireEvent}). Area = exactly one of
 * aoi_id / bbox; period = overlap; AOI rules and the range of the layer as {@link EventArea} and {@link KrArgCoverage}
 * state them; limits from {@code emberline.limits.*} (fire_events_days, fire_events_limit_max, layer_bbox_area_deg2).
 */
@Service
class FireEventQueryService implements ListFireEventsUseCase, GetFireEventUseCase {

    /** Contract schema FireEventId. */
    static final Pattern EVENT_ID = Pattern.compile("^(KR|ARG)_\\d{8}_\\d{4,6}$");
    static final int DEFAULT_LIMIT = 100;
    static final int DEFAULT_MIN_DET = 1;

    private final FireInfoReadPort port;
    private final CatalogApi catalog;
    private final LayerSupport layers;
    private final EmberlineProperties.Limits limits;

    FireEventQueryService(FireInfoReadPort port, CatalogApi catalog, LayerSupport layers, EmberlineProperties properties) {
        this.port = port;
        this.catalog = catalog;
        this.layers = layers;
        this.limits = properties.limits();
    }

    @Override
    public FireEventListResponse list(ReleaseRef release, FireEventListQuery query) {
        boolean hasAoi = Params.given(query.aoiId());
        boolean hasBbox = Params.given(query.bbox());
        Params.exactlyOneArea(hasAoi, hasBbox);
        LocalDate start = Params.requiredDate(query.start(), "start");
        LocalDate end = Params.requiredDate(query.end(), "end");
        if (start.isAfter(end)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "end", "end must not be before start");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > limits.fireEventsDays()) {
            throw new InvalidRequestException(ErrorCode.EVENTS_RANGE_TOO_LARGE, "end", "start..end is limited to "
                    + limits.fireEventsDays() + " days (requested " + days + ")");
        }
        EventSort sort = query.sort() == null ? EventSort.SUM_WEIGHT : EventSort.ofCode(query.sort()).orElseThrow(() ->
                new InvalidRequestException(ErrorCode.INVALID_PARAM, "sort", "sort must be sum_weight, start_date or burned_area_km2"));
        int limit = query.limit() == null ? DEFAULT_LIMIT : query.limit();
        if (limit < 1 || limit > limits.fireEventsLimitMax()) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "limit", "limit must be within 1.." + limits.fireEventsLimitMax());
        }
        int minDet = query.minDet() == null ? DEFAULT_MIN_DET : query.minDet();
        Bbox box = hasBbox ? Params.bbox(query.bbox()) : null;
        if (box != null) {
            Params.bboxArea(box, limits.layerBboxAreaDeg2());
        }
        Optional<EventArea> area = hasBbox ? Optional.of(EventArea.of(box)).filter(a -> KrArgCoverage.covers(box)) : aoiArea(release, query.aoiId());
        String areaKey = hasBbox ? "bbox=" + box.canonical() : "aoi_id=" + query.aoiId();
        String selection = fingerprint(areaKey, start, end, minDet, sort);
        String cursor = Params.given(query.cursor()) ? query.cursor() : null;
        Optional<EventPosition> after = Optional.ofNullable(cursor).map(c -> FireEventCursor.decode(c, release.dataVersion(), selection));
        String normalized = Params.normalized("aoi_id", hasAoi ? query.aoiId() : null, "bbox", hasBbox ? box.canonical() : null,
                "start", start, "end", end, "min_det", minDet, "sort", sort.code(), "limit", limit, "cursor", cursor);
        List<Double> bboxOut = box == null ? null : box.asList();
        String aoiOut = hasAoi ? query.aoiId() : null;
        return layers.cached(release, LayerRoute.FIRE_EVENTS, normalized, FireEventListResponse.class, () -> {
            layers.require(release, LayerFile.FIRE_EVENTS);
            if (area.isEmpty()) {
                return new FireEventListResponse(aoiOut, bboxOut, start.toString(), end.toString(), minDet, sort.code(), 0, 0L,
                        false, null, List.of(), Coverage.unavailable(Coverage.Scope.KR_ARG_EVENTS, Coverage.Reason.OUT_OF_COVERAGE),
                        FireInfoTexts.EVENTS_NOTICE, release.dataRelease());
            }
            EventSelection sel = new EventSelection(area.get(), start, end, minDet, sort);
            long total = port.eventCount(release.releaseId(), sel);
            List<FireEvent> rows = port.eventPage(release.releaseId(), sel, after, limit + 1);
            boolean truncated = rows.size() > limit;
            List<FireEvent> page = truncated ? rows.subList(0, limit) : rows;
            String next = truncated ? FireEventCursor.encode(release.dataVersion(), selection,
                    EventPosition.after(sort, page.get(page.size() - 1))) : null;
            return new FireEventListResponse(aoiOut, bboxOut, start.toString(), end.toString(), minDet, sort.code(), page.size(),
                    total, truncated, next, page.stream().map(FireEventItem::of).toList(),
                    Coverage.availableFor(Coverage.Scope.KR_ARG_EVENTS), FireInfoTexts.EVENTS_NOTICE, release.dataRelease());
        });
    }

    @Override
    public FireEventDetailResponse event(ReleaseRef release, String eventId) {
        if (eventId == null || !EVENT_ID.matcher(eventId).matches()) {
            throw new InvalidRequestException(ErrorCode.INVALID_EVENT_ID, "event_id",
                    "event_id must be <KR|ARG>_<YYYYMMDD>_<serial>, e.g. KR_20001102_0001");
        }
        return layers.cached(release, LayerRoute.FIRE_EVENT, Params.normalized("event_id", eventId), FireEventDetailResponse.class, () -> {
            layers.require(release, LayerFile.FIRE_EVENTS);
            FireEvent e = port.event(release.releaseId(), eventId).orElseThrow(() -> new ResourceNotFoundException(
                    ErrorCode.UNKNOWN_FIRE_EVENT, "event_id", "event " + eventId + " is not in this release"));
            return new FireEventDetailResponse(FireEventItem.of(e), FireInfoTexts.BURNED_AREA_LABEL, release.dataRelease());
        });
    }

    /** The rule of an existing AOI (404 unknown_aoi otherwise); empty when the AOI is outside the layer's range. */
    private Optional<EventArea> aoiArea(ReleaseRef release, String aoiId) {
        AoiSummary aoi = catalog.requireAoi(release, aoiId, "aoi_id");
        if (!KrArgCoverage.covers(aoiId)) {
            return Optional.empty();
        }
        return EventArea.of(aoiId, aoi.nameKo());
    }

    private static String fingerprint(String area, LocalDate start, LocalDate end, int minDet, EventSort sort) {
        String seed = String.join("\n", area, start.toString(), end.toString(), Integer.toString(minDet), sort.code());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
