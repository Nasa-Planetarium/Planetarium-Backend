package io.emberline.outlook.application.port.in;

import java.util.List;

/**
 * The CSV representation: server-made file name ({@code [A-Za-z0-9._-]} only), the contract's {@code x-csv-columns}
 * as header, one list of cells per row (numbers as numbers, text formula-guarded on write).
 */
public record OccurrenceCsv(String fileName, List<String> header, List<List<Object>> rows) {

    public OccurrenceCsv {
        fileName = fileName.replaceAll("[^A-Za-z0-9._-]", "-");
        header = List.copyOf(header);
        rows = List.copyOf(rows);
    }
}
