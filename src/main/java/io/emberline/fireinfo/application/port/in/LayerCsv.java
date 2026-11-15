package io.emberline.fireinfo.application.port.in;

import java.util.List;
import java.util.Map;

/** A layer response that also has a text/csv form (contract {@code x-csv-columns}); written by the CSV converter. */
public interface LayerCsv {

    String csvFileName();

    List<String> csvHeader();

    List<List<Object>> csvRows();

    /** Response headers carrying JSON fields the rows do not (paging). */
    default Map<String, String> csvHeaders() {
        return Map.of();
    }
}
