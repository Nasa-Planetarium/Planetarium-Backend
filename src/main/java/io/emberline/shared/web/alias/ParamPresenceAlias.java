package io.emberline.shared.web.alias;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One alias path serving several canonical operations chosen by which parameter is present, e.g. {@code /api/summary}:
 * {@code aoi_id} → the AOI summary, {@code bbox} → /api/v1/summaries. The first rule whose parameter is present wins;
 * with none present the first rule answers (its canonical route then rejects the request).
 */
public record ParamPresenceAlias(String aliasPath, List<Choice> choices) implements HandoffAliasRule {

    /** A parameter name and the rule it selects. */
    public record Choice(String param, HandoffAliasRule rule) {
    }

    @Override
    public Canonical rewrite(Map<String, String[]> params, String rawQuery) {
        return chosen(params).rewrite(params, rawQuery);
    }

    @Override
    public Optional<Rejection> rejection(Map<String, String[]> params) {
        return chosen(params).rejection(params);
    }

    private HandoffAliasRule chosen(Map<String, String[]> params) {
        return choices.stream().filter(c -> params.containsKey(c.param())).findFirst().orElse(choices.getFirst()).rule();
    }
}
