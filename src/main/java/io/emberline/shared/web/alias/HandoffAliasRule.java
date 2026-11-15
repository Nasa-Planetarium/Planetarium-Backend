package io.emberline.shared.web.alias;

import io.emberline.shared.error.ErrorCode;
import java.util.Map;
import java.util.Optional;

/** One row of contract/handoff-aliases.md: how a hand-off path maps to its canonical /api/v1 request. */
public interface HandoffAliasRule {

    /** The hand-off path as served, e.g. {@code /api/aoi}. */
    String aliasPath();

    /** Canonical request for the given query parameters (the alias's own parameter names). */
    Canonical rewrite(Map<String, String[]> params, String rawQuery);

    /** A request the alias refuses instead of forwarding (written as a problem by the filter); empty by default. */
    default Optional<Rejection> rejection(Map<String, String[]> params) {
        return Optional.empty();
    }

    /** Why an alias request is refused: error code, the parameter at fault and a detail for the problem body. */
    record Rejection(ErrorCode code, String param, String detail) {
    }

    /** A canonical request: path (no query), its parameters, and the query string for the Link header. */
    record Canonical(String path, Map<String, String[]> params, String query) {

        public String link() {
            return query == null || query.isEmpty() ? path : path + "?" + query;
        }
    }
}
