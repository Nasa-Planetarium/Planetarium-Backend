package io.emberline.harmonized.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Summary body (contract schema Summary + team api_contract.md month_residual / residual fields); also its CSV. */
public record SummaryResponse(String aoiId, LocalDate start, LocalDate end, String freq, String unit,
                              List<SummaryPoint> series,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<MonthResidual> monthResidual,
                              SummaryTotal total, String methodVersion) implements CsvTable {

    /** contract x-csv-columns, then the v1.1 and extension columns. */
    static final List<String> HEADER = List.of("area", "date", "value", "value_lo", "value_hi", "value_source",
            "data_source", "status", "missing_dates", "freq", "method_version", "value_projected", "as_of_date", "flags",
            "basis", "residual_prorated");

    @Override
    public String csvFileName() {
        return "emberline_summary_" + CsvTable.safeName(aoiId) + "_" + start + "_" + end + "_" + freq + ".csv";
    }

    @Override
    public List<String> csvHeader() {
        return HEADER;
    }

    @Override
    public List<List<Object>> csvRows() {
        List<List<Object>> rows = new ArrayList<>(series.size());
        for (SummaryPoint p : series) {
            rows.add(Arrays.asList(aoiId, p.date(), p.value(), p.valueLo(), p.valueHi(), p.valueSource(), p.dataSource(),
                    p.status(), p.missingDates(), freq, methodVersion, p.valueProjected(), p.asOfDate(), p.flags(),
                    p.basis(), p.residualProrated()));
        }
        return rows;
    }
}
