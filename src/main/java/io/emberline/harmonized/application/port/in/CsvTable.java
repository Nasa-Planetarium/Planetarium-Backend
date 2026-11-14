package io.emberline.harmonized.application.port.in;

import java.util.List;

/**
 * A result that can also be served as {@code text/csv} (same resource, negotiated): the server-made file name
 * ({@code [A-Za-z0-9._-]} only), the header row and one list of cells per row. Strings are formula-guarded on write;
 * numbers are written as numbers.
 */
public interface CsvTable {

    String csvFileName();

    List<String> csvHeader();

    List<List<Object>> csvRows();

    /** A file-name part reduced to {@code [A-Za-z0-9._-]} (anything else becomes {@code -}). */
    static String safeName(String part) {
        return part == null ? "" : part.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
