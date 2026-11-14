package io.emberline.provenance.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed explanations served with /methods (prototype v3 method body): value-source periods (release month bounds),
 * status codes, the verdict rule the model team applied (explained, never re-applied), data rules and source credits.
 */
public final class MethodDescription {

    public static final List<Map<String, Object>> VALUE_SOURCES = List.of(
            period("modis_m2v_terra", "2000-11", "2002-06", "추정 (Terra)", null),
            period("modis_m2v", "2002-07", "2012-01", "추정 (MODIS → VIIRS)", null),
            period("snpp_observed", "2012-02", "2026-10", "실측", null),
            period("n20_bridge", "2026-11", null, "실측 (NOAA-20 환산 × 1.013)", 1.013),
            period("n21_bridge", null, null, "대체 위성 (NOAA-21 × 1.053, NOAA-20 결측일)", 1.053),
            cellDayOnly("modis_m2v_background", "배경 예측 운반 행 (MODIS 기간 감지 없는 칸의 m2v 예측, 1일 행의 residual_month에만 실림, 값 0)"),
            cellDayOnly("modis_fallback_m2v_predicted", "MODIS 대체 (NOAA-20·NOAA-21을 쓸 수 없는 NRT 날, MODIS 점 × m2v 예측 배율)"));
    public static final List<String> STATUSES = List.of("ok", "nrt", "partial", "missing", "out_of_range");
    public static final Map<String, Object> VERDICT_RULE = verdictRule();
    public static final List<Map<String, Object>> RULES = List.of(
            Map.of("id", "values_as_is", "text", "values, verdicts, percentiles and statuses are the release's; nothing is recomputed"),
            Map.of("id", "zero_vs_missing", "text", "a cell-month without a row is 0; a missing month (status missing) has value null, never 0"),
            Map.of("id", "interval", "text", "value may lie outside [value_lo, value_hi] (separate point and interval models); the value is kept and that interval is not used"),
            Map.of("id", "aoi_totals", "text", "area totals are aoi_month values; summing /cells inside an area gives a different number"),
            Map.of("id", "label", "text", "unit is satellite fire detections on the VIIRS S-NPP basis, not fire counts; 2000-2012-01 are estimates — show value_lo..value_hi"));
    public static final List<Map<String, Object>> SOURCES = List.of(
            Map.of("name", "NASA FIRMS", "url", "https://firms.modaps.eosdis.nasa.gov/",
                    "credit", "We acknowledge the use of data and imagery from LANCE FIRMS operated by NASA's Earth Science Data and Information System (ESDIS)."),
            Map.of("name", "Boundaries", "license", "CC BY", "note", "see the team's docs/references.md"));

    private MethodDescription() {
    }

    private static Map<String, Object> period(String source, String from, String to, String labelKo, Double factor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value_source", source);
        m.put("from", from);
        m.put("to", to);
        m.put("label_ko", labelKo);
        if (factor != null) {
            m.put("factor", factor);
        }
        return m;
    }

    /** A code that only harmonized_cell_day.parquet carries (ruling R-VS1); never an aoi_month or cell-month value_source. */
    private static Map<String, Object> cellDayOnly(String source, String labelKo) {
        Map<String, Object> m = period(source, null, null, labelKo, null);
        m.put("files", List.of("harmonized_cell_day"));
        return m;
    }

    private static Map<String, Object> verdictRule() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "pct-80-20/n10/ok-only");
        m.put("above_if_percentile_ge", 80);
        m.put("below_if_percentile_le", 20);
        m.put("uncertain_if_years_lt", 10);
        m.put("baseline", Map.of("from", 2001, "to", 2025, "same_month", true, "exclude_evaluated_year", true, "status", "ok"));
        m.put("note", "the file's verdict is served as-is (e.g. low_sample rows with value >= 3 are above in the release)");
        return m;
    }
}
