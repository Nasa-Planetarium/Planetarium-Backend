package io.emberline.harmonized.application.port.in;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;

/** Calendar body (contract schema Calendar + v1.1 fields); also its CSV representation. */
public record CalendarResponse(String aoiId, String methodVersion, String unit, int startYear, int endYear,
                               List<String> eventColumns, List<CalendarMonth> months,
                               @JsonIgnore String aoiName) implements CsvTable {

    /** contract x-csv-columns, then the v1.1 and extension columns. */
    static final List<String> HEADER = List.of("aoi_id", "year", "month", "value", "value_lo", "value_hi", "raw_modis",
            "raw_viirs", "value_source", "data_source", "status", "verdict", "percentile", "ratio_to_median", "n_cells",
            "missing_dates", "method_version", "value_projected", "as_of_date", "low_sample", "flags", "aoi_name");

    @Override
    public String csvFileName() {
        return "emberline_calendar_" + CsvTable.safeName(aoiId) + "_" + startYear + "_" + endYear + ".csv";
    }

    @Override
    public List<String> csvHeader() {
        return HEADER;
    }

    @Override
    public List<List<Object>> csvRows() {
        List<List<Object>> rows = new ArrayList<>(months.size());
        for (CalendarMonth m : months) {
            rows.add(java.util.Arrays.asList(aoiId, m.year(), m.month(), m.value(), m.valueLo(), m.valueHi(), m.rawModis(),
                    m.rawViirs(), m.valueSource(), m.dataSource(), m.status(), m.verdict(), m.percentile(),
                    m.ratioToMedian(), m.nCells(), m.missingDates(), methodVersion, m.valueProjected(), m.asOfDate(),
                    m.lowSample(), m.flags(), aoiName));
        }
        return rows;
    }
}
