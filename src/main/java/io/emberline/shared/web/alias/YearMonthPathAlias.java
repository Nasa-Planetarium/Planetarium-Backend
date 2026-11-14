package io.emberline.shared.web.alias;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alias whose {@code year} and {@code month} query parameters become one {@code {year_month}} path segment
 * ({@code YYYY-MM}, month accepted without a leading zero), e.g. {@code /api/compare?aoi_id=KR&year=2025&month=3} →
 * {@code /api/v1/aois/KR/months/2025-03/comparison}. Values that are not numbers are passed through unchanged, so
 * the canonical route rejects them (400).
 */
public record YearMonthPathAlias(String aliasPath, String canonicalTemplate, List<String> pathParams) implements HandoffAliasRule {

    @Override
    public Canonical rewrite(Map<String, String[]> params, String rawQuery) {
        Map<String, String[]> rest = new LinkedHashMap<>(params);
        String year = first(rest.remove("year"));
        String month = first(rest.remove("month"));
        rest.put("year_month", new String[] {yearMonth(year, month)});
        Canonical c = new PathParamAlias(aliasPath, canonicalTemplate, pathParams).rewrite(rest, rawQuery);
        return new Canonical(c.path(), c.params(), c.query());
    }

    static String yearMonth(String year, String month) {
        if (year != null && month != null && year.matches("^\\d{4}$") && month.matches("^\\d{1,2}$")) {
            return year + "-" + (month.length() == 1 ? "0" + month : month);
        }
        return (year == null ? "" : year) + "-" + (month == null ? "" : month);
    }

    private static String first(String[] v) {
        return v == null || v.length == 0 ? null : v[0];
    }
}
