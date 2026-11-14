package io.emberline.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

/**
 * Release folders for tests, derived from the owner fixture {@code core} (real values): broken variants for the
 * validator and v0-named / second-release variants. Each variant gets its own SHA256SUMS.
 */
public final class ReleaseFixtures {

    static final List<String> FILES = List.of("aoi_boundaries.geojson", "aoi_list.parquet", "aoi_month.parquet",
            "aoi_month_compare.parquet", "harmonized_cell_month.parquet", "m2v_meta.json");

    private ReleaseFixtures() {
    }

    /** A fresh release root with a copy of {@code core}. */
    public static Path newRoot() {
        try {
            Path root = Files.createTempDirectory("emberline-releases");
            copyCore(root, "core");
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path copyCore(Path root, String key) {
        try {
            Path dir = Files.createDirectories(root.resolve(key));
            Path core = Fixtures.releaseRoot().resolve("core");
            for (String f : Stream.concat(FILES.stream(), Stream.of("SHA256SUMS")).toList()) {
                Files.copy(core.resolve(f), dir.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Rewrites one parquet file of a copied release with a DuckDB SELECT over the original ({@code src}). */
    public static void rewrite(Path dir, String file, String selectOverSrc) {
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
            resum(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void rewriteText(Path dir, String file, String from, String to) {
        try {
            Path p = dir.resolve(file);
            Files.writeString(p, Files.readString(p).replace(from, to));
            resum(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes SHA256SUMS for the six release files. */
    public static void resum(Path dir) {
        StringBuilder sb = new StringBuilder();
        for (String f : FILES) {
            sb.append(Fixtures.sha256(dir.resolve(f))).append("  ").append(f).append('\n');
        }
        try {
            Files.writeString(dir.resolve("SHA256SUMS"), sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
