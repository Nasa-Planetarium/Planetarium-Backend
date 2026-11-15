package io.emberline.shared.web.alias;

import io.emberline.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One value accepted under several names (the model team's two documents name the AOI {@code aoi} and {@code aoi_id},
 * MAPPING §8.1): whichever name is present becomes {@code canonicalName} for {@code rule}. Two or more of the names in
 * one request are refused with 400 {@link ErrorCode#AOI_AND_BBOX} (40003) — no silent precedence between them.
 */
public record EitherNameAlias(String aliasPath, List<String> names, String canonicalName, HandoffAliasRule rule)
        implements HandoffAliasRule {

    @Override
    public Canonical rewrite(Map<String, String[]> params, String rawQuery) {
        Map<String, String[]> renamed = new LinkedHashMap<>(params);
        String[] value = null;
        for (String name : names) {
            String[] v = renamed.remove(name);
            value = value == null ? v : value;
        }
        if (value != null) {
            renamed.put(canonicalName, value);
        }
        return rule.rewrite(renamed, rawQuery);
    }

    @Override
    public Optional<Rejection> rejection(Map<String, String[]> params) {
        List<String> given = names.stream().filter(params::containsKey).toList();
        if (given.size() < 2) {
            return rule.rejection(params);
        }
        return Optional.of(new Rejection(ErrorCode.AOI_AND_BBOX, given.get(1),
                "give the area as one of " + String.join(" or ", names) + ", not " + String.join(" and ", given)));
    }
}
