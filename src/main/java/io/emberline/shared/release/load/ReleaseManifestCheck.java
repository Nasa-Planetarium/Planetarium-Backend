package io.emberline.shared.release.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * {@code release_manifest.csv} ({@code file,rows,bytes,sha256}, v1.2) of a release folder, checked once per stage before
 * any loader validates or loads. Ingest's file check calls it next to the SHA256SUMS check and hands in the same sha256
 * per file, so no file is hashed twice. Without the file nothing is checked here (v1, v1.1).
 * <ul>
 * <li>a listed file that is not in the folder rejects: {@code MANIFEST_FILE_MISSING:<file>};</li>
 * <li>a listed file the loaders read must match its bytes ({@code MANIFEST_BYTES_MISMATCH:<file>:<listed>!=<actual>}),
 * its sha256 ({@code MANIFEST_SHA256_MISMATCH:<file>}) and, where {@code rows} is filled, its parquet row count
 * ({@code MANIFEST_ROWS_MISMATCH:<file>:<listed>!=<actual>});</li>
 * <li>a loader file that the manifest lists only under a subdirectory (the model team's {@code models/m2v_meta.json})
 * and that sits at the top of the folder (where the loader reads it) is that entry's copy: it is checked against the entry
 * by base name, and its reasons name the top-level file ({@code MANIFEST_SHA256_MISMATCH:m2v_meta.json});</li>
 * <li>a file of the folder that is not listed is a warning: {@code MANIFEST_UNLISTED:<file>};</li>
 * <li>a manifest that does not parse rejects: {@code MANIFEST_INVALID:release_manifest.csv:<line>}.</li>
 * </ul>
 */
@Component
public class ReleaseManifestCheck {

    public static final String FILE = "release_manifest.csv";
    static final String HEADER = "file,rows,bytes,sha256";
    /** Folder files that describe the release rather than belong to it. */
    static final Set<String> NOT_LISTED = Set.of(FILE, "SHA256SUMS");
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._=-]+(/[A-Za-z0-9._=-]+)*$");
    static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    static final Pattern COUNT = Pattern.compile("^[0-9]{1,18}$");

    /** One manifest line; {@code rows} is null when the column is empty (JSON and GeoJSON files). */
    record Entry(String file, Long rows, long bytes, String sha256) {
    }

    private final DuckDb duckDb;

    public ReleaseManifestCheck(DuckDb duckDb) {
        this.duckDb = duckDb;
    }

    /**
     * @param dir         the release folder (already resolved inside the release root)
     * @param loaderFiles names of the files the loaders read (see {@link ReleaseLoader#files()})
     * @param sha256      hex sha256 of a file (memoised by the caller)
     */
    public void check(Path dir, Collection<String> loaderFiles, Function<Path, String> sha256, ValidationReport report) {
        Path csv = dir.resolve(FILE);
        if (!Files.isRegularFile(csv)) {
            return;
        }
        Map<String, Entry> entries = parse(csv, report);
        if (entries == null) {
            return;
        }
        for (Entry e : entries.values()) {
            Path file = dir.resolve(e.file());
            if (!Files.isRegularFile(file)) {
                report.reject("MANIFEST_FILE_MISSING", e.file(), null);
            } else if (loaderFiles.contains(e.file())) {
                verify(file, e.file(), e, sha256, report);
            }
        }
        Map<String, Entry> copies = topLevelCopies(dir, entries, loaderFiles);
        copies.forEach((name, e) -> verify(dir.resolve(name), name, e, sha256, report));
        for (String name : folderFiles(dir)) {
            if (!entries.containsKey(name) && !copies.containsKey(name)) {
                report.warn("MANIFEST_UNLISTED", name, null);
            }
        }
    }

    /**
     * Loader files at the top of the folder that the manifest lists only under a subdirectory, each with that entry
     * (top-level name → entry). A base name listed under two subdirectories is not guessed: neither entry applies, so
     * the file stays unlisted.
     */
    static Map<String, Entry> topLevelCopies(Path dir, Map<String, Entry> entries, Collection<String> loaderFiles) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (String name : loaderFiles) {
            if (name.contains("/") || entries.containsKey(name) || !Files.isRegularFile(dir.resolve(name))) {
                continue;
            }
            List<Entry> nested = entries.values().stream()
                    .filter(e -> e.file().contains("/") && e.file().substring(e.file().lastIndexOf('/') + 1).equals(name))
                    .toList();
            if (nested.size() == 1) {
                out.put(name, nested.get(0));
            }
        }
        return out;
    }

    /** {@code file} against entry {@code e}; reasons name {@code name} (the file as the folder has it). */
    private void verify(Path file, String name, Entry e, Function<Path, String> sha256, ValidationReport report) {
        long bytes = size(file);
        if (bytes != e.bytes()) {
            report.reject("MANIFEST_BYTES_MISMATCH", name, e.bytes() + "!=" + bytes);
            return;
        }
        if (!e.sha256().equalsIgnoreCase(sha256.apply(file))) {
            report.reject("MANIFEST_SHA256_MISMATCH", name, null);
            return;
        }
        if (e.rows() == null) {
            return;
        }
        if (!name.endsWith(".parquet")) {
            report.warn("MANIFEST_ROWS_UNCHECKED", name, "rows are counted for parquet files only");
            return;
        }
        try (Connection duck = duckDb.open(); PreparedStatement ps = duck.prepareStatement(DuckSql.text("shared/duck_rows.sql"))) {
            ps.setString(1, file.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long rows = rs.getLong(1);
                if (rows != e.rows()) {
                    report.reject("MANIFEST_ROWS_MISMATCH", name, e.rows() + "!=" + rows);
                }
            }
        } catch (SQLException ex) {
            report.reject("FILE_UNREADABLE", name, null);
        }
    }

    /** file → entry in manifest order; null (and a reason) when a line does not parse. */
    static Map<String, Entry> parse(Path csv, ValidationReport report) {
        List<String> lines;
        try {
            lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report.reject("FILE_UNREADABLE", FILE, null);
            return null;
        }
        if (lines.isEmpty() || !lines.get(0).replace("\uFEFF", "").strip().equals(HEADER)) {
            report.reject("MANIFEST_INVALID", FILE, "1");
            return null;
        }
        Map<String, Entry> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",", -1);
            boolean ok = f.length == 4 && NAME.matcher(f[0]).matches() && List.of(f[0].split("/")).stream().noneMatch(seg -> seg.equals("..") || seg.equals("."))
                    && (f[1].isEmpty() || COUNT.matcher(f[1]).matches()) && COUNT.matcher(f[2]).matches()
                    && SHA256.matcher(f[3]).matches() && !out.containsKey(f[0]);
            if (!ok) {
                report.reject("MANIFEST_INVALID", FILE, Integer.toString(i + 1));
                return null;
            }
            out.put(f[0], new Entry(f[0], f[1].isEmpty() ? null : Long.parseLong(f[1]), Long.parseLong(f[2]), f[3]));
        }
        return out;
    }

    /** Regular files directly in the folder, sorted, without dot files and the manifests themselves. */
    static List<String> folderFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith(".") && !NOT_LISTED.contains(n)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
