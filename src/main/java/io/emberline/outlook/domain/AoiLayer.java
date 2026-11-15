package io.emberline.outlook.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The AOI file of one release: its head (one target month and as-of date) and the rows in aoi_id order. */
public record AoiLayer(String targetMonth, String asOfDate, List<AoiRow> rows, Map<String, AoiRow> byId) {

    public AoiLayer(String targetMonth, String asOfDate, List<AoiRow> rows) {
        this(targetMonth, asOfDate, List.copyOf(rows),
                rows.stream().collect(Collectors.toUnmodifiableMap(AoiRow::aoiId, Function.identity())));
    }

    public Optional<AoiRow> find(String aoiId) {
        return Optional.ofNullable(byId.get(aoiId));
    }
}
