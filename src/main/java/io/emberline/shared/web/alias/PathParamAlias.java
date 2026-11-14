package io.emberline.shared.web.alias;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Alias whose query parameters become path variables, e.g. {@code /api/calendar?aoi_id=KR&start_year=2024} →
 * {@code /api/v1/aois/KR/calendar?start_year=2024}. A missing path parameter keeps the literal placeholder text
 * out of the path by using an empty segment, which the canonical route then rejects as 404/400.
 */
public record PathParamAlias(String aliasPath, String canonicalTemplate, List<String> pathParams) implements HandoffAliasRule {

    @Override
    public Canonical rewrite(Map<String, String[]> params, String rawQuery) {
        String path = canonicalTemplate;
        Map<String, String[]> rest = new LinkedHashMap<>(params);
        for (String name : pathParams) {
            String[] v = rest.remove(name);
            String value = v == null || v.length == 0 ? "" : v[0];
            path = path.replace("{" + name + "}", URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        StringJoiner query = new StringJoiner("&");
        rest.forEach((k, values) -> {
            for (String value : values) {
                query.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        return new Canonical(path, rest, query.toString());
    }
}
