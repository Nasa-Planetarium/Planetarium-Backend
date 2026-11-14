package io.emberline.shared.web.alias;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Alias whose hand-off parameters {@code year} + {@code month} (e.g. 2025 and 3) become the canonical {@code month}
 * = {@code YYYY-MM} (/api/cells → /api/v1/cells). Anything that is not two integers is passed on unchanged as
 * {@code month} so the canonical route's validation answers 400.
 */
public record YearMonthAlias(String aliasPath, String canonicalPath) implements HandoffAliasRule {

    @Override
    public Canonical rewrite(Map<String, String[]> params, String rawQuery) {
        Map<String, String[]> out = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (!k.equals("year") && !k.equals("month")) {
                out.put(k, v);
            }
        });
        String year = first(params, "year");
        String month = first(params, "month");
        if (year != null || month != null) {
            out.put("month", new String[] {yearMonth(year, month)});
        }
        String query = out.entrySet().stream()
                .flatMap(e -> java.util.Arrays.stream(e.getValue()).map(v -> enc(e.getKey()) + "=" + enc(v)))
                .collect(Collectors.joining("&"));
        return new Canonical(canonicalPath, out, query);
    }

    static String yearMonth(String year, String month) {
        if (year != null && month != null && year.matches("^\\d{4}$") && month.matches("^\\d{1,2}$")) {
            return year + "-" + (month.length() == 1 ? "0" + month : month);
        }
        return month != null && year == null ? month : String.valueOf(year) + "-" + month;
    }

    private static String first(Map<String, String[]> params, String name) {
        String[] v = params.get(name);
        return v == null || v.length == 0 ? null : v[0];
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
