package io.emberline.fireinfo.application.port.in;

import io.emberline.fireinfo.domain.FwiDay;
import java.util.ArrayList;
import java.util.List;

/** Contract schema FwiDay: one AOI-day of fwi_aoi_day.parquet as stored. */
public record FwiDayItem(String date, Double fwiMean, Double fwiMax, String fwiClass, Double fwiPct, String fwiRelClass,
                         Double isiMean, Double buiMean, Double ffmcMean, Double dcMean, Integer nCells) {

    public static FwiDayItem of(FwiDay d) {
        return new FwiDayItem(d.date().toString(), d.fwiMean(), d.fwiMax(), d.fwiClass(), d.fwiPct(), d.fwiRelClass(),
                d.isiMean(), d.buiMean(), d.ffmcMean(), d.dcMean(), d.nCells());
    }

    /** Contract x-csv-columns order: aoi_id, date … n_cells, as_of_date. */
    List<Object> csvRow(String aoiId, String asOfDate) {
        List<Object> row = new ArrayList<>(13);
        row.add(aoiId);
        row.add(date);
        row.add(fwiMean);
        row.add(fwiMax);
        row.add(fwiClass);
        row.add(fwiPct);
        row.add(fwiRelClass);
        row.add(isiMean);
        row.add(buiMean);
        row.add(ffmcMean);
        row.add(dcMean);
        row.add(nCells);
        row.add(asOfDate);
        return row;
    }
}
