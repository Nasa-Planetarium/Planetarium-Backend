package io.emberline.harmonized.adapter.in.web;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * One CSV cell. Text that a spreadsheet would run as a formula (starting with {@code = + - @}, tab or CR) gets a
 * leading {@code '}; numbers are written as numbers (a negative value stays {@code -3.5}). Quoting is RFC 4180:
 * a cell with a comma, quote or line break is quoted and quotes are doubled.
 */
final class CsvCells {

    private CsvCells() {
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d) {
            return d.isNaN() || d.isInfinite() ? "" : BigDecimal.valueOf(d).toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Collection<?> list) {
            return text(list.stream().map(String::valueOf).collect(Collectors.joining(";")));
        }
        return text(value.toString());
    }

    static String text(String s) {
        String guarded = s.isEmpty() || "=+-@\t\r".indexOf(s.charAt(0)) < 0 ? s : "'" + s;
        if (guarded.indexOf(',') >= 0 || guarded.indexOf('"') >= 0 || guarded.indexOf('\n') >= 0 || guarded.indexOf('\r') >= 0) {
            return "\"" + guarded.replace("\"", "\"\"") + "\"";
        }
        return guarded;
    }
}
