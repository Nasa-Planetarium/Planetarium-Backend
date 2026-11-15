package io.emberline.observation.application.service;

import io.emberline.catalog.api.CatalogApi;
import io.emberline.harmonized.api.AoiCellsApi;
import io.emberline.observation.application.port.in.CellFrpList;
import io.emberline.observation.application.port.in.CellFrpQuery;
import io.emberline.observation.application.port.in.ListCellFrpUseCase;
import io.emberline.observation.application.port.out.FrpReadPort;
import io.emberline.observation.domain.CellBox;
import io.emberline.observation.domain.CellGrid;
import io.emberline.observation.domain.CellIdRange;
import io.emberline.observation.domain.FrpRow;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.json.Coverage;
import io.emberline.shared.release.ReleaseRef;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * GET /api/v1/cells/frp (contract listCellFrp). Exactly one of {@code aoi_id} (cells of the AOI by ruling R-SP1,
 * harmonized's cell set) or {@code bbox} (cell center inside the box, the /cells rule). {@code freq=day} = the rows as
 * stored, {@code freq=month} = per cell and month Σ frp_sum, Σ frp_snpp_equiv_sum, max frp_snpp_equiv_max, Σ n_points.
 * Limits ({@code emberline.limits.*}, contract text "응답 행(칸 × 기간 추정) ≤ 50,000, 일 단위 기간 ≤ 31일, 월 단위 ≤ 24개월,
 * bbox 넓이 ≤ 100 deg² — 넘으면 400 range_too_large"): the period and the box area are checked before any read
 * (40104 / 40105); the rows are counted by reading at most frp_rows + 1 of them (40104). Answers are cached under
 * data_version + the normalized query; errors are never cached.
 */
@Service
class FrpQueryService implements ListCellFrpUseCase {

    static final LayerRoute ROUTE = LayerRoute.FRP;
    /** Contract CellFrpList.notice: 「불 세기(FRP, S-NPP 기준)」 + the model team's limitation text of /cells/frp. */
    static final String NOTICE = "불 세기(FRP, S-NPP 기준) — Terra 밤 비율(2.41)은 신뢰가 낮고, 2000~2011 MODIS 는 2012 이후 비율을 쓴 값(가정)";
    static final String MONTH = "month";
    static final String DAY = "day";

    private final FrpReadPort frp;
    private final CatalogApi catalog;
    private final AoiCellsApi aoiCells;
    private final LayerCache cache;
    private final EmberlineProperties.Limits limits;

    FrpQueryService(FrpReadPort frp, CatalogApi catalog, AoiCellsApi aoiCells, LayerCache cache, EmberlineProperties properties) {
        this.frp = frp;
        this.catalog = catalog;
        this.aoiCells = aoiCells;
        this.cache = cache;
        this.limits = properties.limits();
    }

    @Override
    public CellFrpList frp(ReleaseRef release, CellFrpQuery query) {
        boolean byAoi = query.aoiId() != null && !query.aoiId().isEmpty();
        boolean byBbox = query.bbox() != null && !query.bbox().isEmpty();
        if (byAoi && byBbox) {
            throw new InvalidRequestException(ErrorCode.AOI_AND_BBOX, "bbox", "give either aoi_id or bbox, not both");
        }
        if (!byAoi && !byBbox) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "bbox", "give exactly one of aoi_id or bbox");
        }
        if (query.start().isAfter(query.end())) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "start", "start must not be after end");
        }
        boolean month = MONTH.equals(query.freq());
        checkPeriod(query.start(), query.end(), month);
        CellBox box = byBbox ? CellQueryService.parseBbox(query.bbox()) : null;
        if (box != null && area(box) > limits.frpBboxAreaDeg2()) {
            throw new InvalidRequestException(ErrorCode.BBOX_AREA_TOO_LARGE, "bbox", "bbox covers at most "
                    + limits.frpBboxAreaDeg2() + " square degrees on /cells/frp; choose a smaller box");
        }
        String normalized = (byAoi ? "aoi_id=" + query.aoiId() : "bbox=" + box.west() + "," + box.south() + "," + box.east()
                + "," + box.north()) + "&end=" + query.end() + "&freq=" + (month ? MONTH : DAY) + "&start=" + query.start();
        return cache.get(ROUTE, release, normalized, CellFrpList.class,
                () -> answer(release, byAoi ? query.aoiId() : null, box, query.start(), query.end(), month));
    }

    /** Day period ≤ frp_day_days (start..end inclusive), month period ≤ frp_month_months (calendar months touched). */
    private void checkPeriod(LocalDate start, LocalDate end, boolean month) {
        if (month) {
            long months = ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1;
            if (months > limits.frpMonthMonths()) {
                throw new InvalidRequestException(ErrorCode.FRP_RANGE_TOO_LARGE, "end", "freq=month covers at most "
                        + limits.frpMonthMonths() + " months (start..end touches " + months + "); split the period");
            }
        } else {
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (days > limits.frpDayDays()) {
                throw new InvalidRequestException(ErrorCode.FRP_RANGE_TOO_LARGE, "end", "freq=day covers at most "
                        + limits.frpDayDays() + " days (start..end has " + days + "); use freq=month or a shorter period");
            }
        }
    }

    private CellFrpList answer(ReleaseRef release, String aoiId, CellBox box, LocalDate start, LocalDate end, boolean month) {
        if (!frp.loaded(release.releaseId())) {
            throw new DataUnavailableException(ErrorCode.LAYER_NOT_LOADED, "harmonized_cell_day_frp is not loaded in this release");
        }
        long[] cells = null;
        CellIdRange range;
        Coverage coverage = Coverage.availableFor(Coverage.Scope.WORLD_FRP);
        if (aoiId != null) {
            catalog.requireAoi(release, aoiId, "aoi_id");
            Optional<long[]> inside = aoiCells.cellsInside(release, aoiId);
            if (inside.isEmpty() || inside.get().length == 0) {
                return list(release, aoiId, null, start, end, month, List.of(),
                        Coverage.unavailable(Coverage.Scope.WORLD_FRP, Coverage.Reason.NO_CELLS));
            }
            cells = inside.get();
            range = CellIdRange.enclosing(cells);
        } else {
            range = CellIdRange.of(box);
        }
        List<FrpRow> rows = range.isEmpty() ? List.of() : month
                ? frp.months(release.releaseId(), start, end, range, cells, limits.frpRows() + 1)
                : frp.days(release.releaseId(), start, end, range, cells, limits.frpRows() + 1);
        if (rows.size() > limits.frpRows()) {
            throw new InvalidRequestException(ErrorCode.FRP_RANGE_TOO_LARGE, null, "the answer has more than "
                    + limits.frpRows() + " rows; choose a smaller area or a shorter period");
        }
        List<CellFrpList.Cell> out = new ArrayList<>(rows.size());
        for (FrpRow r : rows) {
            out.add(new CellFrpList.Cell(r.cellId(), CellGrid.centerLat(r.cellId()), CellGrid.centerLon(r.cellId()),
                    r.date().toString(), zero(r.frpSum()), zero(r.frpSnppEquivSum()), zero(r.frpSnppEquivMax()), r.nPoints()));
        }
        return list(release, aoiId, box, start, end, month, out, coverage);
    }

    private static CellFrpList list(ReleaseRef release, String aoiId, CellBox box, LocalDate start, LocalDate end,
                                    boolean month, List<CellFrpList.Cell> cells, Coverage coverage) {
        return new CellFrpList(aoiId, box == null ? null : List.of(box.west(), box.south(), box.east(), box.north()),
                start.toString(), end.toString(), month ? MONTH : DAY, cells.size(), cells, coverage, NOTICE,
                release.dataRelease());
    }

    static double area(CellBox box) {
        return (box.east() - box.west()) * (box.north() - box.south());
    }

    /** The file's {@code -0.0} (its statistics minimum) is written as 0; a stored 0 stays a row with value 0. */
    static Double zero(Double v) {
        return v != null && v == 0.0 ? Double.valueOf(0.0) : v;
    }

    static Float zero(Float v) {
        return v != null && v == 0.0f ? Float.valueOf(0.0f) : v;
    }
}
