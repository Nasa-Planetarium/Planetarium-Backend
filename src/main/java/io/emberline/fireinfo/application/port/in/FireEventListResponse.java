package io.emberline.fireinfo.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.emberline.shared.json.Coverage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GET /api/v1/fire-events (contract schema FireEventList). Exactly one of {@code aoi_id} / {@code bbox} is present.
 * {@code count} = events in this page, {@code total} = every matching event, {@code truncated} = more follow
 * ({@code next_cursor}). Out of range: {@code events: []} with {@code coverage.available=false}.
 */
public record FireEventListResponse(@JsonInclude(JsonInclude.Include.NON_NULL) String aoiId,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) List<Double> bbox,
                                    String start, String end, int minDet, String sort, int count, Long total,
                                    boolean truncated, String nextCursor, List<FireEventItem> events, Coverage coverage,
                                    String notice, String dataRelease) implements LayerCsv {

    public FireEventListResponse {
        bbox = bbox == null ? null : List.copyOf(bbox);
        events = List.copyOf(events);
    }

    @Override
    public String csvFileName() {
        String area = aoiId != null ? aoiId : bbox.stream().map(String::valueOf).collect(Collectors.joining("_"));
        return "emberline_fire_events_" + area + "_" + start + "_" + end + ".csv";
    }

    @Override
    public List<String> csvHeader() {
        return FireEventItem.CSV_COLUMNS;
    }

    @Override
    public List<List<Object>> csvRows() {
        return events.stream().map(FireEventItem::csvRow).toList();
    }

    /** The page fields of the JSON that are not event columns: X-Truncated, X-Total-Count, X-Next-Cursor. */
    @Override
    public Map<String, String> csvHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Truncated", Boolean.toString(truncated));
        if (total != null) {
            h.put("X-Total-Count", Long.toString(total));
        }
        if (nextCursor != null) {
            h.put("X-Next-Cursor", nextCursor);
        }
        return h;
    }
}
