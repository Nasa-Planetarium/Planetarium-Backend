package io.emberline.outlook.application.port.in;

import io.emberline.outlook.domain.ForecastPeriod;
import io.emberline.outlook.domain.ForecastRegion;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A validated {@code POST /forecast} query. {@link #json()} is the canonical body sent to the forecast service and the
 * normalized query of the cache key: members sorted by name ({@code end}, {@code horizon_days}, {@code region},
 * {@code start}), no spaces, numbers in one form ({@link ForecastRegion}).
 */
public record ForecastQuery(ForecastRegion region, ForecastPeriod period) {

    public ForecastQuery {
        Objects.requireNonNull(region);
        Objects.requireNonNull(period);
    }

    public String json() {
        Map<String, String> members = new TreeMap<>(period.members());
        members.put(ForecastRegion.PARAM, region.json());
        return members.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
