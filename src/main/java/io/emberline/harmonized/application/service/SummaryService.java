package io.emberline.harmonized.application.service;

import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.harmonized.application.port.in.GetSummaryUseCase;
import io.emberline.harmonized.application.port.in.MonthResidual;
import io.emberline.harmonized.application.port.in.SummaryPoint;
import io.emberline.harmonized.application.port.in.SummaryQuery;
import io.emberline.harmonized.application.port.in.SummaryResponse;
import io.emberline.harmonized.application.port.in.SummaryTotal;
import io.emberline.harmonized.application.port.out.AoiCellsPort;
import io.emberline.harmonized.application.port.out.CellDayQueryPort;
import io.emberline.harmonized.application.port.out.ResidualQueryPort;
import io.emberline.harmonized.domain.AoiMonths;
import io.emberline.harmonized.domain.DayTotal;
import io.emberline.harmonized.domain.MonthRecord;
import io.emberline.harmonized.domain.Reading;
import io.emberline.harmonized.domain.ResidualSums;
import io.emberline.harmonized.domain.Status;
import io.emberline.harmonized.domain.cases.ReadCaseRegistry;
import io.emberline.shared.Units;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.release.ReleaseRef;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * AOI summary (ruling R-SP1, team api_contract.md):
 * <ul>
 * <li>freq=month: a month wholly inside [start, end] is the aoi_month row as stored (value already = Σ cell value +
 * Σ residual_month). A month the range cuts is Σ harmonized_cell_day of the AOI's cells over the included days plus
 * residual_month × included_days / days_in_month ({@code residual_prorated: true}).</li>
 * <li>freq=day: Σ cell_day.value per date (residual never added to a day) plus {@code month_residual} per month
 * touched; the range total adds the (pro-rated) residual.</li>
 * <li>Month in progress (status partial): month-to-date value, value_projected / as_of_date passed through, no
 * residual; days after as_of_date are out_of_range.</li>
 * </ul>
 */
@Service
class SummaryService implements GetSummaryUseCase {

    static final String ROUTE = "summary";
    static final String TERRA = "modis_m2v_terra";
    static final String PARTIAL_RANGE = "partial_range";
    static final String BACKGROUND = "modis_m2v_background";

    private final CatalogApi catalog;
    private final AoiMonthsProvider months;
    private final AoiCellsPort cells;
    private final CellDayQueryPort cellDay;
    private final ResidualQueryPort residuals;
    private final EmberlineProperties.Limits limits;

    SummaryService(CatalogApi catalog, AoiMonthsProvider months, AoiCellsPort cells, CellDayQueryPort cellDay,
                   ResidualQueryPort residuals, EmberlineProperties properties) {
        this.catalog = catalog;
        this.months = months;
        this.cells = cells;
        this.cellDay = cellDay;
        this.residuals = residuals;
        this.limits = properties.limits();
    }

    @Override
    public SummaryResponse summary(ReleaseRef release, String aoiId, SummaryQuery q) {
        check(q);
        AoiSummary aoi = catalog.requireAoi(release, aoiId, "aoi_id");
        AoiMonths bundle = months.get(release, aoi.aoiId(), ROUTE);
        Area area = new Area(release, aoi.aoiId());
        return "day".equals(q.freq()) ? daySummary(release, bundle, area, q) : monthSummary(release, bundle, area, q);
    }

    private void check(SummaryQuery q) {
        if (q.start().isAfter(q.end())) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "start", "start must not be after end");
        }
        if ("day".equals(q.freq()) && ChronoUnit.DAYS.between(q.start(), q.end()) + 1 > limits.summaryDayDays()) {
            throw new InvalidRequestException(ErrorCode.CALENDAR_RANGE_TOO_LARGE, "end",
                    "freq=day covers at most " + limits.summaryDayDays() + " days; narrow start..end or use freq=month");
        }
        if (q.end().isAfter(q.start().plusYears(limits.summaryMonthYears()))) {
            throw new InvalidRequestException(ErrorCode.CALENDAR_RANGE_TOO_LARGE, "end",
                    "a summary covers at most " + limits.summaryMonthYears() + " years; narrow start..end");
        }
    }

    // ── freq=month ──

    private SummaryResponse monthSummary(ReleaseRef release, AoiMonths bundle, Area area, SummaryQuery q) {
        List<SummaryPoint> series = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(q.start()); !ym.isAfter(YearMonth.from(q.end())); ym = ym.plusMonths(1)) {
            LocalDate from = max(q.start(), ym.atDay(1));
            LocalDate to = min(q.end(), ym.atEndOfMonth());
            boolean whole = from.equals(ym.atDay(1)) && to.equals(ym.atEndOfMonth());
            Optional<MonthRecord> row = bundle.at(ym.getYear(), ym.getMonthValue());
            if (row.isEmpty()) {
                series.add(monthPoint(ym, Reading.outOfRange(), null, null, "aoi_month", whole ? null : false));
            } else if (whole) {
                MonthRecord m = row.get();
                series.add(monthPoint(ym, ReadCaseRegistry.apply(m.reading()), m.valueSource(), m.dataSource(), "aoi_month", false));
            } else {
                series.add(cutMonth(release, area, row.get(), ym, from, to));
            }
        }
        return new SummaryResponse(area.aoiId, q.start(), q.end(), "month", Units.UNIT, series, null,
                total(series, null), release.methodVersion());
    }

    /** A month the range cuts: Σ cell_day over the included days + the month residual pro rata. */
    private SummaryPoint cutMonth(ReleaseRef release, Area area, MonthRecord m, YearMonth ym, LocalDate from, LocalDate to) {
        if (!Status.hasValue(m.status())) {
            Reading r = ReadCaseRegistry.apply(Reading.of(null, null, null, m.status())).flagged(PARTIAL_RANGE);
            return monthPoint(ym, r, m.valueSource(), m.dataSource(), "cell_day", false);
        }
        long[] ids = area.cells();
        List<DayTotal> days = cellDay.dayTotals(release, ids, from, to);
        double value = 0;
        Double lo = TERRA.equals(m.valueSource()) ? null : 0.0;
        Double hi = lo;
        for (DayTotal d : days) {
            value += d.value();
            lo = addOrNull(lo, d.valueLo());
            hi = addOrNull(hi, d.valueHi());
        }
        boolean partial = Status.PARTIAL.equals(m.status());
        double share = 0;
        if (!partial) {
            ResidualSums sums = cellDay.monthResiduals(release, ids, ym, ym).get(ym);
            double residual = sums == null ? 0.0 : sums.total();
            share = residual * (ChronoUnit.DAYS.between(from, to) + 1) / ym.lengthOfMonth();
        }
        Reading r = Reading.of(value + share, lo == null ? null : lo + share, hi == null ? null : hi + share, m.status());
        if (partial) {
            r = r.withProjection(m.valueProjected(), m.asOfDate());
        }
        r = ReadCaseRegistry.apply(r).flagged(PARTIAL_RANGE);
        return monthPoint(ym, r, m.valueSource(), m.dataSource(), "cell_day", !partial);
    }

    private static SummaryPoint monthPoint(YearMonth ym, Reading r, String valueSource, String dataSource, String basis,
                                           Boolean prorated) {
        return new SummaryPoint(ym.atDay(1), ym.getYear(), ym.getMonthValue(), r.value(), r.valueLo(), r.valueHi(),
                r.valueProjected(), r.asOfDate(), valueSource, dataSource, r.status(), List.of(), r.flags(), basis, prorated);
    }

    // ── freq=day ──

    private SummaryResponse daySummary(ReleaseRef release, AoiMonths bundle, Area area, SummaryQuery q) {
        long[] ids = area.cells();
        Map<LocalDate, DayTotal> totals = new HashMap<>();
        for (DayTotal t : cellDay.dayTotals(release, ids, q.start(), q.end())) {
            totals.put(t.date(), t);
        }
        List<SummaryPoint> series = new ArrayList<>();
        for (LocalDate d = q.start(); !d.isAfter(q.end()); d = d.plusDays(1)) {
            series.add(dayPoint(bundle.at(d.getYear(), d.getMonthValue()), d, totals.get(d)));
        }
        YearMonth first = YearMonth.from(q.start());
        YearMonth last = YearMonth.from(q.end());
        Map<YearMonth, ResidualSums> residual = cellDay.monthResiduals(release, ids, first, last);
        Map<YearMonth, Map<String, Double>> byType = residuals.available(release)
                ? residuals.residualsByType(release, ids, first, last) : Map.of();
        List<MonthResidual> monthResiduals = new ArrayList<>();
        for (YearMonth ym = first; !ym.isAfter(last); ym = ym.plusMonths(1)) {
            LocalDate from = max(q.start(), ym.atDay(1));
            LocalDate to = min(q.end(), ym.atEndOfMonth());
            int included = (int) ChronoUnit.DAYS.between(from, to) + 1;
            monthResiduals.add(monthResidual(bundle.at(ym.getYear(), ym.getMonthValue()), ym, included,
                    residual.get(ym), byType.getOrDefault(ym, Map.of())));
        }
        return new SummaryResponse(area.aoiId, q.start(), q.end(), "day", Units.UNIT, series, monthResiduals,
                total(series, monthResiduals), release.methodVersion());
    }

    private static SummaryPoint dayPoint(Optional<MonthRecord> row, LocalDate d, DayTotal t) {
        if (row.isEmpty() || afterAsOf(row.get(), d)) {
            Reading r = Reading.outOfRange();
            return new SummaryPoint(d, null, null, null, null, null, null, null, row.map(MonthRecord::valueSource).orElse(null),
                    null, r.status(), List.of(), r.flags(), "cell_day", null);
        }
        MonthRecord m = row.get();
        Reading r;
        if (!Status.hasValue(m.status())) {
            r = Reading.of(null, null, null, m.status());
        } else if (t == null) {
            Double bound = TERRA.equals(m.valueSource()) ? null : 0.0;
            r = Reading.of(0.0, bound, bound, m.status());
        } else {
            r = Reading.of(t.value(), t.valueLo(), t.valueHi(), m.status());
        }
        r = ReadCaseRegistry.apply(r);
        String source = t != null && t.valueSource() != null ? t.valueSource() : m.valueSource();
        return new SummaryPoint(d, null, null, r.value(), r.valueLo(), r.valueHi(), null, null, source, m.dataSource(),
                r.status(), List.of(), r.flags(), "cell_day", null);
    }

    private static boolean afterAsOf(MonthRecord m, LocalDate d) {
        return Status.PARTIAL.equals(m.status()) && m.asOfDate() != null && d.isAfter(m.asOfDate());
    }

    /**
     * by_type: Σ value per residual_type of harmonized_residual_cell_month plus {@code modis_m2v_background} (the
     * background-prediction carrier rows of cell_day, which the residual file does not hold).
     */
    private static MonthResidual monthResidual(Optional<MonthRecord> row, YearMonth ym, int included, ResidualSums stored,
                                               Map<String, Double> fileTypes) {
        int days = ym.lengthOfMonth();
        boolean prorated = included < days;
        if (row.isEmpty() || !Status.hasValue(row.get().status())) {
            return new MonthResidual(ym.getYear(), ym.getMonthValue(), null, prorated, included, days, null, fileTypes,
                    MonthResidual.NOTE);
        }
        boolean none = Status.PARTIAL.equals(row.get().status()) || stored == null;
        double residual = none ? 0.0 : stored.total();
        Map<String, Double> byType = new java.util.TreeMap<>(fileTypes);
        if (!none && stored.background() != 0) {
            byType.put(BACKGROUND, stored.background());
        }
        double includedShare = prorated ? residual * included / days : residual;
        return new MonthResidual(ym.getYear(), ym.getMonthValue(), residual, prorated, included, days, includedShare,
                byType, MonthResidual.NOTE);
    }

    // ── totals ──

    private static SummaryTotal total(List<SummaryPoint> series, List<MonthResidual> monthResiduals) {
        double value = 0;
        double lo = 0;
        double hi = 0;
        boolean interval = true;
        int known = 0;
        int missing = 0;
        int outOfRange = 0;
        boolean prorated = false;
        for (SummaryPoint p : series) {
            missing += Status.MISSING.equals(p.status()) ? 1 : 0;
            outOfRange += Status.OUT_OF_RANGE.equals(p.status()) ? 1 : 0;
            prorated |= Boolean.TRUE.equals(p.residualProrated());
            if (p.value() == null) {
                continue;
            }
            known++;
            value += p.value();
            interval &= p.valueLo() != null && p.valueHi() != null;
            lo += p.valueLo() == null ? 0 : p.valueLo();
            hi += p.valueHi() == null ? 0 : p.valueHi();
        }
        Double residualIncluded = null;
        if (monthResiduals != null) {
            double r = 0;
            for (MonthResidual m : monthResiduals) {
                r += m.residualIncluded() == null ? 0 : m.residualIncluded();
                prorated |= m.residualProrated() && m.residualIncluded() != null && m.residualIncluded() != 0;
            }
            residualIncluded = r;
            value += r;
            lo += r;
            hi += r;
        }
        boolean any = known > 0;
        return new SummaryTotal(any ? value : null, any && interval ? lo : null, any && interval ? hi : null, missing,
                outOfRange, residualIncluded, prorated);
    }

    // ── helpers ──

    private static Double addOrNull(Double sum, Double add) {
        return sum == null || add == null ? null : sum + add;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    /** The AOI's cell set, resolved once per request (and cached per release + AOI by the adapter). */
    private final class Area {
        private final ReleaseRef release;
        private final String aoiId;
        private long[] ids;

        Area(ReleaseRef release, String aoiId) {
            this.release = release;
            this.aoiId = aoiId;
        }

        long[] cells() {
            if (ids == null) {
                if (!cellDay.available(release)) {
                    throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null,
                            "day-level data (harmonized_cell_day) is not part of the active release");
                }
                ids = cells.cellsInside(release, aoiId).orElseThrow(() -> new DataUnavailableException(
                        ErrorCode.DATA_NOT_LOADED, "aoi_id", "this area has no boundary in the release; day-level sums are not available"));
            }
            return ids;
        }
    }
}
