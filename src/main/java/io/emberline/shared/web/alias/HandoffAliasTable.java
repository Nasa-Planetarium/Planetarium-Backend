package io.emberline.shared.web.alias;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * contract/handoff-aliases.md as code. Each later packet adds its rows here (B2: /api/calendar, /api/compare,
 * /api/summary (aoi_id; bbox since pl-sum), /api/export.csv type=calendar|summary, type=hotspots since pl-fix2; B3: /api/hotspots, /api/cells; B4: /api/firms/wms) — no alias controllers.
 */
@Component
public class HandoffAliasTable {

    private final Map<String, HandoffAliasRule> rules;

    public HandoffAliasTable() {
        this(List.of(
                new SamePathAlias("/api/aoi", "/api/v1/aois"),
                new SamePathAlias("/api/method", "/api/v1/methods/current"),
                new SamePathAlias("/api/status", "/api/v1/satellite-status"),
                new YearMonthAlias("/api/cells", "/api/v1/cells"),
                new SamePathAlias("/api/hotspots", "/api/v1/hotspots"),
                new SamePathAlias("/api/firms/wms", "/api/v1/firms/wms-tiles"),
                new PathParamAlias("/api/calendar", "/api/v1/aois/{aoi_id}/calendar", List.of("aoi_id")),
                new YearMonthPathAlias("/api/compare", "/api/v1/aois/{aoi_id}/months/{year_month}/comparison", List.of("aoi_id", "year_month")),
                new ParamPresenceAlias("/api/summary", List.of(
                        new ParamPresenceAlias.Choice("aoi_id", new PathParamAlias("/api/summary", "/api/v1/aois/{aoi_id}/summary", List.of("aoi_id"))),
                        new ParamPresenceAlias.Choice("bbox", new SamePathAlias("/api/summary", "/api/v1/summaries")))),
                new ParamDispatchAlias("/api/export.csv", "type", Map.of(
                        "calendar", new PathParamAlias("/api/export.csv", "/api/v1/aois/{aoi_id}/calendar", List.of("aoi_id")),
                        "summary", new PathParamAlias("/api/export.csv", "/api/v1/aois/{aoi_id}/summary", List.of("aoi_id")),
                        "hotspots", new PathParamAlias("/api/export.csv", "/api/v1/hotspots", List.of())),
                        Map.of("format", "csv"))));
    }

    public HandoffAliasTable(List<HandoffAliasRule> rules) {
        this.rules = rules.stream().collect(Collectors.toUnmodifiableMap(HandoffAliasRule::aliasPath, Function.identity()));
    }

    public Optional<HandoffAliasRule> find(String path) {
        return Optional.ofNullable(rules.get(path));
    }
}
