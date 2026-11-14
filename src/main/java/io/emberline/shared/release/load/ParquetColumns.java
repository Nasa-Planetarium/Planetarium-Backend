package io.emberline.shared.release.load;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the hand-off column names of one parquet file to its physical columns (aliases accepted and reported),
 * checks presence and kind, and renders SQL templates whose {@code {column}} placeholders become quoted identifiers
 * (or NULL for an absent optional column).
 */
public final class ParquetColumns {

    private final Map<String, String> expr = new LinkedHashMap<>();
    private final Map<String, Boolean> present = new HashMap<>();
    private final boolean ok;

    private ParquetColumns(boolean ok) {
        this.ok = ok;
    }

    public static ParquetColumns inspect(Connection duck, String file, String fileName, List<ColumnSpec> specs,
                                         ValidationReport report) throws SQLException {
        Map<String, String> physical = new LinkedHashMap<>();
        try (PreparedStatement ps = duck.prepareStatement(DuckSql.text("shared/duck_columns.sql"))) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    physical.put(md.getColumnName(i), md.getColumnTypeName(i));
                }
            }
        }
        boolean ok = true;
        ParquetColumns out = new ParquetColumns(true);
        for (ColumnSpec spec : specs) {
            String found = physical.containsKey(spec.name()) ? spec.name() : null;
            for (String alias : spec.aliases()) {
                if (found == null && physical.containsKey(alias)) {
                    found = alias;
                    report.alias(alias, spec.name());
                }
            }
            if (found == null) {
                out.expr.put(spec.name(), "NULL");
                out.present.put(spec.name(), false);
                if (spec.required()) {
                    report.reject("COLUMN_MISSING", fileName, spec.name());
                    ok = false;
                } else {
                    report.warn("COLUMN_ABSENT", fileName, spec.name());
                }
                continue;
            }
            if (!spec.kind().accepts(physical.get(found))) {
                report.reject("COLUMN_TYPE", fileName, spec.name() + "=" + physical.get(found));
                ok = false;
            }
            out.expr.put(spec.name(), quote(found));
            out.present.put(spec.name(), true);
        }
        return ok ? out : withOk(out, false);
    }

    private static ParquetColumns withOk(ParquetColumns source, boolean ok) {
        ParquetColumns copy = new ParquetColumns(ok);
        copy.expr.putAll(source.expr);
        copy.present.putAll(source.present);
        return copy;
    }

    public boolean ok() {
        return ok;
    }

    public boolean has(String column) {
        return present.getOrDefault(column, false);
    }

    /** Replaces every {@code {column}} placeholder of the template. */
    public String render(String template) {
        String sql = template;
        for (Map.Entry<String, String> e : expr.entrySet()) {
            sql = sql.replace("{" + e.getKey() + "}", e.getValue());
        }
        return sql;
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
