package io.emberline.harmonized.application.service;

import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.harmonized.application.port.in.BaselineYear;
import io.emberline.harmonized.application.port.in.ComparisonResponse;
import io.emberline.harmonized.application.port.in.GetComparisonUseCase;
import io.emberline.harmonized.domain.AoiMonths;
import io.emberline.harmonized.domain.MonthRecord;
import io.emberline.harmonized.domain.Reading;
import io.emberline.harmonized.domain.Status;
import io.emberline.harmonized.domain.cases.ReadCaseRegistry;
import io.emberline.shared.Units;
import io.emberline.shared.release.ReleaseRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Same-month comparison = the stored aoi_month_compare row (verdict, percentile, ratio_to_median, baseline fields,
 * baseline_period, continuity_ratio, continuity_source) — never recomputed. The baseline list only shows which stored
 * months the rule describes (the row's baseline_period — 2001–2025 when the release has none —, same month, other
 * years, status ok); {@code rule} explains the stored verdict, it is not evaluated.
 */
@Service
class ComparisonService implements GetComparisonUseCase {

    static final String ROUTE = "comparison";
    static final int BASELINE_FROM = 2001;
    static final int BASELINE_TO = 2025;
    static final Map<String, Object> RULE;

    static {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", "pct-80-20/n10/ok-only");
        rule.put("above_if_percentile_ge", 80);
        rule.put("below_if_percentile_le", 20);
        rule.put("uncertain_if_years_lt", 10);
        rule.put("low_sample", "baseline_p75 < 1; stored verdict is above when value >= 3, else uncertain");
        rule.put("partial_month", "uncertain");
        rule.put("baseline_period", "the row's baseline_period (2001-2025 or 2012-2025 by the model's continuity check); "
                + "months before 2012 of a 2012-2025 region are uncertain");
        rule.put("source", "aoi_month_compare as stored (not recomputed)");
        RULE = java.util.Collections.unmodifiableMap(rule);
    }

    private final CatalogApi catalog;
    private final AoiMonthsProvider months;

    ComparisonService(CatalogApi catalog, AoiMonthsProvider months) {
        this.catalog = catalog;
        this.months = months;
    }

    @Override
    public ComparisonResponse comparison(ReleaseRef release, String aoiId, String yearMonth) {
        int year = Integer.parseInt(yearMonth.substring(0, 4));
        int month = Integer.parseInt(yearMonth.substring(5, 7));
        AoiSummary aoi = catalog.requireAoi(release, aoiId, "aoi_id");
        AoiMonths bundle = months.get(release, aoi.aoiId(), ROUTE);
        Optional<MonthRecord> row = bundle.at(year, month);
        int[] window = baselineWindow(row.map(MonthRecord::baselinePeriod).orElse(null));
        List<BaselineYear> baseline = new ArrayList<>();
        for (int by = window[0]; by <= window[1]; by++) {
            if (by != year) {
                baseline.add(baselineYear(bundle, by, month));
            }
        }
        if (row.isEmpty()) {
            return new ComparisonResponse(aoi.aoiId(), year, month, Units.UNIT, null, null, null, null, null, null, null,
                    null, Status.OUT_OF_RANGE, null, null, null, null, null, null, null, null, null, List.of(), baseline,
                    RULE, release.methodVersion());
        }
        MonthRecord m = row.get();
        Reading r = ReadCaseRegistry.apply(m.compareReading());
        return new ComparisonResponse(aoi.aoiId(), year, month, Units.UNIT, r.value(), m.baselineMedian(),
                m.baselineP25(), m.baselineP75(), m.percentile(), m.ratioToMedian(), m.nBaselineYears(), r.verdict(),
                r.status(), m.valueSource(), r.valueLo(), r.valueHi(), r.valueProjected(), r.asOfDate(), m.lowSample(),
                m.baselinePeriod(), m.continuityRatio(), m.continuitySource(), r.flags(), baseline, RULE,
                release.methodVersion());
    }

    /**
     * First and last year of a stored baseline_period ({@code "2001-2025"}, {@code "2012-2025"}); 2001–2025 when the
     * release has no such column (v1, v1.1) or the text is not {@code YYYY-YYYY}.
     */
    static int[] baselineWindow(String baselinePeriod) {
        if (baselinePeriod != null && baselinePeriod.matches("\\d{4}-\\d{4}")) {
            int from = Integer.parseInt(baselinePeriod.substring(0, 4));
            int to = Integer.parseInt(baselinePeriod.substring(5, 9));
            if (from <= to) {
                return new int[] {from, to};
            }
        }
        return new int[] {BASELINE_FROM, BASELINE_TO};
    }

    private static BaselineYear baselineYear(AoiMonths bundle, int year, int month) {
        return bundle.at(year, month).map(b -> {
            Reading r = ReadCaseRegistry.apply(b.reading());
            return new BaselineYear(year, r.value(), r.status(), b.valueSource(),
                    Status.OK.equals(b.status()) && b.value() != null);
        }).orElseGet(() -> new BaselineYear(year, null, Status.OUT_OF_RANGE, null, false));
    }
}
