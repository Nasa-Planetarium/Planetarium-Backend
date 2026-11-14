package io.emberline.hotspots.application.service;

import io.emberline.catalog.api.CatalogApi;
import io.emberline.hotspots.application.port.in.HotspotItem;
import io.emberline.hotspots.application.port.in.HotspotListQuery;
import io.emberline.hotspots.application.port.in.HotspotListResponse;
import io.emberline.hotspots.application.port.in.ListHotspotsUseCase;
import io.emberline.hotspots.application.port.in.ReferenceSum;
import io.emberline.hotspots.application.port.out.HotspotQueryPort;
import io.emberline.hotspots.domain.BboxArea;
import io.emberline.hotspots.domain.Hotspot;
import io.emberline.hotspots.domain.HotspotScan;
import io.emberline.hotspots.domain.Layer;
import io.emberline.hotspots.domain.PagePosition;
import io.emberline.hotspots.domain.PointFilter;
import io.emberline.hotspots.domain.Totals;
import io.emberline.shared.Units;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.release.ReleaseRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Points of the pinned release, only when zoomed in (area ≤ max-area-deg2, span ≤ max-days; otherwise 400 pointing to
 * the cell layer). reference_sum covers every matching reference point; the page follows the public order with a
 * keyset cursor.
 */
@Service
class HotspotQueryService implements ListHotspotsUseCase {

    /** Screen note of the contract: the point sum leaves out the MODIS-period residual, so it can be below /summary. */
    static final String NOTE = "점 합계에는 MODIS 시기(2000-11~2012-01) 잔차(residual)가 들어 있지 않아 같은 지역·기간의 "
            + "/summary 값보다 작을 수 있습니다.";
    static final String NOT_READY = "점 자료(harmonized_hotspots)는 준비 중입니다 — 이 release 에 점 파일이 아직 없습니다.";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_INSTANT;

    private final HotspotQueryPort port;
    private final CatalogApi catalog;
    private final AoiShapes shapes;
    private final HotspotSettings settings;

    HotspotQueryService(HotspotQueryPort port, CatalogApi catalog, AoiShapes shapes, HotspotSettings settings) {
        this.port = port;
        this.catalog = catalog;
        this.shapes = shapes;
        this.settings = settings;
    }

    /** The selection after validation. */
    private record Area(BboxArea bbox, Optional<PointFilter> filter, String param) {
    }

    @Override
    public HotspotListResponse list(ReleaseRef release, HotspotListQuery query) {
        boolean hasBbox = query.bbox() != null && !query.bbox().isEmpty();
        boolean hasAoi = query.aoiId() != null && !query.aoiId().isEmpty();
        if (hasBbox == hasAoi) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "aoi_id",
                    "give exactly one of bbox or aoi_id");
        }
        LocalDate start = date(query.start(), "start");
        LocalDate end = date(query.end(), "end");
        if (start.isAfter(end)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "end", "end must not be before start");
        }
        Layer layer = Layer.ofCode(query.layer() == null ? "reference" : query.layer()).orElseThrow(() ->
                new InvalidRequestException(ErrorCode.INVALID_PARAM, "layer", "layer must be reference or all"));
        Area area = hasBbox ? new Area(bbox(query.bbox()), Optional.empty(), "bbox") : aoi(release, query.aoiId());
        requireZoomedIn(area, start, end);

        List<Path> files = port.files(release.releaseKey(), layer, YearMonth.from(start), YearMonth.from(end))
                .orElseThrow(() -> new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, NOT_READY));
        HotspotScan scan = new HotspotScan(files, start, end, area.bbox(), area.filter());
        String fingerprint = fingerprint(hasBbox ? area.bbox().toString() : query.aoiId(), start, end, layer);
        Optional<PagePosition> after = Optional.ofNullable(query.cursor()).filter(c -> !c.isEmpty())
                .map(c -> HotspotCursor.decode(c, release.dataVersion(), fingerprint));

        Totals totals = port.totals(scan);
        List<Hotspot> rows = port.page(scan, after, query.limit() + 1);
        boolean truncated = rows.size() > query.limit();
        List<Hotspot> page = truncated ? rows.subList(0, query.limit()) : rows;
        String next = truncated ? HotspotCursor.encode(release.dataVersion(), fingerprint, nextPosition(page, after)) : null;

        ReferenceSum sum = new ReferenceSum(totals.value(), totals.valueLo(), totals.valueHi(), totals.nPoints(), NOTE);
        List<HotspotItem> items = page.stream().map(HotspotQueryService::item).toList();
        List<Double> bboxOut = hasBbox ? List.of(area.bbox().west(), area.bbox().south(), area.bbox().east(), area.bbox().north()) : null;
        return new HotspotListResponse(hasAoi ? query.aoiId() : null, bboxOut, start.toString(), end.toString(), layer.code(),
                query.limit(), items.size(), totals.total(), truncated, next, Units.UNIT, sum, items, release.methodVersion());
    }

    private void requireZoomedIn(Area area, LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > settings.maxDays()) {
            throw new InvalidRequestException(ErrorCode.OBSERVATION_RANGE_TOO_LARGE, "end",
                    "점은 기간 " + settings.maxDays() + "일 이하에서만 냅니다(요청 " + days + "일). "
                            + "넓은 범위는 칸 지도(/cells)를 쓰세요.");
        }
        double deg2 = area.bbox().areaDeg2();
        if (deg2 > settings.maxAreaDeg2()) {
            throw new InvalidRequestException(ErrorCode.OBSERVATION_RANGE_TOO_LARGE, area.param(),
                    "점은 지도를 확대했을 때(영역 " + plain(settings.maxAreaDeg2()) + " 제곱도 이하)만 냅니다(요청 "
                            + plain(Math.round(deg2 * 100) / 100.0) + " 제곱도). 넓은 범위는 칸 지도(/cells)를 쓰세요.");
        }
    }

    private Area aoi(ReleaseRef release, String aoiId) {
        catalog.requireAoi(release, aoiId, "aoi_id");
        AoiShapes.Shape shape = shapes.of(release, aoiId).orElseThrow(() -> new InvalidRequestException(
                ErrorCode.INVALID_PARAM, "aoi_id", "aoi_id " + aoiId + " has no boundary in this release; use bbox"));
        return new Area(shape.bbox(), Optional.of(shape.inside()), "aoi_id");
    }

    static BboxArea bbox(String text) {
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
        return new BboxArea(v[0], v[1], v[2], v[3]);
    }

    private static InvalidRequestException badBbox() {
        return new InvalidRequestException(ErrorCode.INVALID_PARAM, "bbox",
                "bbox must be west,south,east,north with west < east, south < north, longitudes within ±180 and latitudes within ±90");
    }

    private static LocalDate date(String text, String param) {
        if (text == null || text.isEmpty()) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " is required");
        }
        try {
            return LocalDate.parse(text, DATE);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " must be a calendar date YYYY-MM-DD");
        }
    }

    /** Position after the last point of {@code page}: its key, and how many points with that key were returned so far. */
    private static PagePosition nextPosition(List<Hotspot> page, Optional<PagePosition> after) {
        Hotspot last = page.get(page.size() - 1);
        int same = (int) page.stream().filter(h -> h.key().equals(last.key())).count();
        int carried = after.filter(a -> a.key().equals(last.key())).map(PagePosition::skip).orElse(0);
        return new PagePosition(last.key(), same + carried);
    }

    private static HotspotItem item(Hotspot h) {
        return new HotspotItem(h.latitude(), h.longitude(), UTC.format(h.acqDatetimeUtc()), h.dateLocal().toString(),
                h.satellite(), h.instrument(), h.frp(), h.confidence(), h.daynight(), h.cellId(), h.adm0A3(), h.adm1Code(),
                h.harmonizedWeight(), h.weightLo(), h.weightHi(), h.weightSource(), h.isReference(), h.source(),
                h.methodVersion());
    }

    private static String fingerprint(String area, LocalDate start, LocalDate end, Layer layer) {
        String seed = String.join("\n", area, start.toString(), end.toString(), layer.code());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String plain(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
