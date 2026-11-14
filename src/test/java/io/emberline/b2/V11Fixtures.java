package io.emberline.b2;

import io.emberline.support.Fixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Release folders from the owner fixture core-v11 (v1.1, 8 files, real values) for the B2 tests. */
final class V11Fixtures {

    static final List<String> FILES = List.of("aoi_boundaries.geojson", "aoi_list.parquet", "aoi_month.parquet",
            "aoi_month_compare.parquet", "harmonized_cell_day.parquet", "harmonized_cell_month.parquet",
            "harmonized_residual_cell_month.parquet", "m2v_meta.json");

    private V11Fixtures() {
    }

    static Path dir() {
        return Fixtures.releaseRoot().resolve("core-v11");
    }

    static Path file(String name) {
        return dir().resolve(name);
    }

    /** A fresh release root (own temp parent) with a copy of core-v11 under {@code key}. */
    static Path newRoot(String key) {
        try {
            Path root = Files.createDirectory(Files.createTempDirectory("emberline-b2").resolve("releases"));
            copy(root, key);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path copy(Path root, String key) {
        try {
            Path dir = Files.createDirectories(root.resolve(key));
            for (String f : FILES) {
                Files.copy(dir().resolve(f), dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(dir().resolve("SHA256SUMS"), dir.resolve("SHA256SUMS"), StandardCopyOption.REPLACE_EXISTING);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies the v1 owner fixture {@code core} (6 files, no day tables) under {@code key}. */
    static Path copyV1(Path root, String key) {
        try {
            Path dir = Files.createDirectories(root.resolve(key));
            Path core = Fixtures.releaseRoot().resolve("core");
            try (var files = Files.list(core)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Rewrites one parquet file with a DuckDB SELECT over the original ({@code src}) and re-sums the 8 files. */
    static void rewrite(Path dir, String file, String selectOverSrc) {
        Path original = dir.resolve(file);
        Path tmp = dir.resolve(file + ".src");
        try {
            Files.move(original, tmp);
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
                c.createStatement().execute("CREATE TABLE src AS SELECT * FROM read_parquet('" + tmp.toString().replace("'", "''") + "')");
                c.createStatement().execute("COPY (" + selectOverSrc + ") TO '" + original.toString().replace("'", "''")
                        + "' (FORMAT parquet)");
            }
            Files.delete(tmp);
            StringBuilder sb = new StringBuilder();
            for (String f : FILES) {
                sb.append(Fixtures.sha256(dir.resolve(f))).append("  ").append(f).append('\n');
            }
            Files.writeString(dir.resolve("SHA256SUMS"), sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static String manifestSha(Path root, String key) {
        return Fixtures.sha256(root.resolve(key).resolve("SHA256SUMS"));
    }
}
