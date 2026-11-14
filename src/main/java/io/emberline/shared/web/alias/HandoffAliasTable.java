package io.emberline.shared.web.alias;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * contract/handoff-aliases.md as code. Each later packet adds its rows here (B2: /api/calendar, /api/compare,
 * /api/summary, /api/export.csv; B3: /api/hotspots, /api/cells; B4: /api/firms/wms) — no alias controllers.
 */
@Component
public class HandoffAliasTable {

    private final Map<String, HandoffAliasRule> rules;

    public HandoffAliasTable() {
        this(List.of(
                new SamePathAlias("/api/aoi", "/api/v1/aois"),
                new SamePathAlias("/api/method", "/api/v1/methods/current"),
                new SamePathAlias("/api/status", "/api/v1/satellite-status"),
                new SamePathAlias("/api/hotspots", "/api/v1/hotspots")));
    }

    public HandoffAliasTable(List<HandoffAliasRule> rules) {
        this.rules = rules.stream().collect(Collectors.toUnmodifiableMap(HandoffAliasRule::aliasPath, Function.identity()));
    }

    public Optional<HandoffAliasRule> find(String path) {
        return Optional.ofNullable(rules.get(path));
    }
}
