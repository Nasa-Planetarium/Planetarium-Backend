package io.emberline.fireinfo.application.port.in;

import io.emberline.shared.json.Coverage;
import java.util.ArrayList;
import java.util.List;

/**
 * GET /api/v1/aois/{aoiId}/fwi (contract schema FwiResponse). {@code as_of_date} is in every answer; {@code day} is
 * null after it, outside the file's days, for a series request and out of range; {@code series} only for start..end.
 */
public record FwiResponse(String aoiId, String name, String asOfDate, String badge, String date, FwiDayItem day,
                          List<FwiDayItem> series, Coverage coverage, String notice, String dataRelease) implements LayerCsv {

    /** Contract x-csv-columns of getAoiFwi. */
    public static final List<String> CSV_COLUMNS = List.of("aoi_id", "date", "fwi_mean", "fwi_max", "fwi_class", "fwi_pct",
            "fwi_rel_class", "isi_mean", "bui_mean", "ffmc_mean", "dc_mean", "n_cells", "as_of_date");

    public FwiResponse {
        series = List.copyOf(series);
    }

    @Override
    public String csvFileName() {
        return "emberline_fwi_" + aoiId + "_" + (date != null ? date : series.isEmpty() ? asOfDate
                : series.get(0).date() + "_" + series.get(series.size() - 1).date()) + ".csv";
    }

    @Override
    public List<String> csvHeader() {
        return CSV_COLUMNS;
    }

    /** The series rows, or the one day; none when there is no value. */
    @Override
    public List<List<Object>> csvRows() {
        List<List<Object>> rows = new ArrayList<>();
        if (!series.isEmpty()) {
            series.forEach(d -> rows.add(d.csvRow(aoiId, asOfDate)));
        } else if (day != null) {
            rows.add(day.csvRow(aoiId, asOfDate));
        }
        return rows;
    }
}
