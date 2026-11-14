package io.emberline.shared.web.alias;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One alias path serving several canonical operations chosen by a parameter, e.g. {@code /api/export.csv?type=calendar}
 * → the calendar rule. The dispatch parameter is dropped and {@code fixed} parameters are added (export: format=csv,
 * so the canonical route negotiates text/csv). An unknown or missing value forwards to a path no route serves (404).
 */
public record ParamDispatchAlias(String aliasPath, String param, Map<String, HandoffAliasRule> rules,
                                 Map<String, String> fixed) implements HandoffAliasRule {

    static final String UNSERVED = "/api/v1/unknown-alias-type";

    @Override
    public Canonical rewrite(Map<String, String[]> params, String rawQuery) {
        Map<String, String[]> rest = new LinkedHashMap<>(params);
        String[] v = rest.remove(param);
        HandoffAliasRule rule = v == null || v.length == 0 ? null : rules.get(v[0]);
        if (rule == null) {
            return new Canonical(UNSERVED, Map.of(), "");
        }
        fixed.forEach((k, value) -> rest.put(k, new String[] {value}));
        return rule.rewrite(rest, rawQuery);
    }
}
