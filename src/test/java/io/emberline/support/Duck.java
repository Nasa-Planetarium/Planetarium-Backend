package io.emberline.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Reads fixture parquet files directly (expected values come from the release files, not from our code). */
public final class Duck {

    private Duck() {
    }

    public static List<List<Object>> query(String sql, Object... params) {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i] instanceof Path p ? p.toString() : params[i]);
            }
            List<List<Object>> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path core(String file) {
        return Fixtures.releaseRoot().resolve("core").resolve(file);
    }
}
