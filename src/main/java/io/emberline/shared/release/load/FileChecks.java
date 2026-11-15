package io.emberline.shared.release.load;

import io.emberline.shared.release.load.cases.DataCase;
import io.emberline.shared.release.load.cases.DataCaseRegistry;
import io.emberline.shared.sql.SqlResource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reusable parquet checks for loaders: code value sets (with v0 translation), duplicate keys, data cases. */
public final class FileChecks {

    private FileChecks() {
    }

    /** Every non-null value of {@code column} must be allowed (after v0 → v1 translation, which is reported). */
    public static void codeValues(Connection duck, String file, String fileName, ParquetColumns cols, String column,
                                  Set<String> allowed, Map<String, String> v0, ValidationReport report) throws SQLException {
        codeValues(duck, file, fileName, cols, column, allowed, v0, report, "CODE_VALUE");
    }

    /** As above with the reason code to use (e.g. METHOD_VERSION_MISMATCH). */
    public static void codeValues(Connection duck, String file, String fileName, ParquetColumns cols, String column,
                                  Set<String> allowed, Map<String, String> v0, ValidationReport report, String reasonCode)
            throws SQLException {
        if (!cols.has(column)) {
            return;
        }
        Set<String> bad = new TreeSet<>();
        String sql = cols.render(SqlResource.text("shared/duck_distinct.sql").replace("{target}", "{" + column + "}"));
        try (PreparedStatement ps = duck.prepareStatement(sql)) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String v = rs.getString(1);
                    if (v == null || allowed.contains(v)) {
                        continue;
                    }
                    String translated = v0.get(v);
                    if (translated != null && allowed.contains(translated)) {
                        report.alias(v, translated);
                    } else {
                        bad.add(v);
                    }
                }
            }
        }
        if (!bad.isEmpty()) {
            report.reject(reasonCode, fileName, column + "=" + String.join("|", bad).replaceAll("[^A-Za-z0-9_|.-]", "?"));
        }
    }

    /** Rejects the file when a key occurs more than once. */
    public static void uniqueKey(Connection duck, String file, String fileName, ParquetColumns cols, String keys,
                                 ValidationReport report) throws SQLException {
        String sql = cols.render(SqlResource.text("shared/duck_duplicates.sql").replace("{keys}", keys));
        try (PreparedStatement ps = duck.prepareStatement(sql)) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long dups = rs.getLong(1);
                if (dups > 0) {
                    report.reject("DUPLICATE_KEY", fileName, dups + " keys");
                }
            }
        }
    }

    /**
     * Runs a counting query whose columns are data cases (see DataCase) and applies their handlers.
     * {@code {interval_noise_tolerance}} becomes {@link DataCase#INTERVAL_NOISE_TOLERANCE_TEXT}.
     */
    public static void dataCases(Connection duck, String file, String fileName, String renderedSql, ValidationReport report)
            throws SQLException {
        String sql = renderedSql.replace("{interval_noise_tolerance}", DataCase.INTERVAL_NOISE_TOLERANCE_TEXT);
        try (PreparedStatement ps = duck.prepareStatement(sql)) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                DataCaseRegistry.apply(fileName, rs, report);
            }
        }
    }

    /** A single long from a one-parameter (file) query. */
    public static long count(Connection duck, String file, String renderedSql) throws SQLException {
        try (PreparedStatement ps = duck.prepareStatement(renderedSql)) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
