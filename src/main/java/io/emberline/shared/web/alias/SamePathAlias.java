package io.emberline.shared.web.alias;

import java.util.Map;

/** Alias whose parameters keep their names (e.g. /api/aoi → /api/v1/aois, /api/status → /api/v1/satellite-status). */
public record SamePathAlias(String aliasPath, String canonicalPath) implements HandoffAliasRule {

    @Override
    public Canonical rewrite(Map<String, String[]> params, String rawQuery) {
        return new Canonical(canonicalPath, params, rawQuery);
    }
}
