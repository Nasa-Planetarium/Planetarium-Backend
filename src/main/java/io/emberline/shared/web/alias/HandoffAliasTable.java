package io.emberline.shared.web.alias;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * contract/handoff-aliases.md as code. Each later work item adds its rows here (/api/calendar, /api/compare,
 * /api/summary (aoi_id; bbox since pl-sum), /api/export.csv type=calendar|summary, type=hotspots since pl-fix2; /api/hotspots, /api/cells; /api/firms/wms;
 * /api/occurrence — {@code unit=grid} → the grid, {@code aoi} or {@code aoi_id} → the AOI;
 * /api/events, /api/fwi, /api/spread, /api/regime with {@code aoi} or {@code aoi_id};
 * POST /api/forecast, body unchanged) — no alias controllers.
 */
@Component
public class HandoffAliasTable {

    /**
     * {@code /api/occurrence?aoi=<id>} (model team api_contract) or {@code ?aoi_id=<id>} (quickstart) →
     * /api/v1/aois/{aoiId}/occurrence; both names together → 400 (MAPPING §8.1, 40003).
     */
    static final HandoffAliasRule AOI_OCCURRENCE = new EitherNameAlias("/api/occurrence", List.of("aoi", "aoi_id"), "aoi_id",
            new PathParamAlias("/api/occurrence", "/api/v1/aois/{aoi_id}/occurrence", List.of("aoi_id")));

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
                new ParamPresenceAlias("/api/occurrence", List.of(
                        new ParamPresenceAlias.Choice("aoi", AOI_OCCURRENCE),
                        new ParamPresenceAlias.Choice("aoi_id", AOI_OCCURRENCE),
                        new ParamPresenceAlias.Choice("unit", new ParamDispatchAlias("/api/occurrence", "unit", Map.of(
                                "grid", new PathParamAlias("/api/occurrence", "/api/v1/occurrence/grid", List.of())), Map.of())))),
                new ParamDispatchAlias("/api/export.csv", "type", Map.of(
                        "calendar", new PathParamAlias("/api/export.csv", "/api/v1/aois/{aoi_id}/calendar", List.of("aoi_id")),
                        "summary", new PathParamAlias("/api/export.csv", "/api/v1/aois/{aoi_id}/summary", List.of("aoi_id")),
                        "hotspots", new PathParamAlias("/api/export.csv", "/api/v1/hotspots", List.of())),
                        Map.of("format", "csv")),
                new AoiParamAlias("/api/events", new SamePathAlias("/api/events", "/api/v1/fire-events")),
                new AoiParamAlias("/api/fwi", new PathParamAlias("/api/fwi", "/api/v1/aois/{aoi_id}/fwi", List.of("aoi_id"))),
                new AoiParamAlias("/api/spread", new SamePathAlias("/api/spread", "/api/v1/spread-nowcast")),
                new AoiParamAlias("/api/regime", new PathParamAlias("/api/regime", "/api/v1/aois/{aoi_id}/fire-regime", List.of("aoi_id"))),
                new SamePathAlias("/api/forecast", "/api/v1/forecast")));
    }

    public HandoffAliasTable(List<HandoffAliasRule> rules) {
        this.rules = rules.stream().collect(Collectors.toUnmodifiableMap(HandoffAliasRule::aliasPath, Function.identity()));
    }

    public Optional<HandoffAliasRule> find(String path) {
        return Optional.ofNullable(rules.get(path));
    }
}
