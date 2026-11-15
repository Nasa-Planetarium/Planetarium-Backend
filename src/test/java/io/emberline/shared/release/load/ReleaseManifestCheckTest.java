package io.emberline.shared.release.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.shared.config.EmberlineProperties;
import io.emberline.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L3-1 (acceptance 1 "sha256 = release_manifest.csv"): the owner fixture core-v12-layers passes its own
 * release_manifest.csv; a copy with one wrong sha256, one wrong byte count, one wrong row count or one listed file
 * removed is refused with a reason naming that file; an unlisted extra file is only a warning. laid out like the real
 * manifest ({@code models/m2v_meta.json} listed, the loader's copy at the top), the top-level copy is checked against that
 * entry by base name.
 */
class ReleaseManifestCheckTest {

    static final Path FIXTURE = Fixtures.releaseRoot().resolve("core-v12-layers");
    static final String FRP = "harmonized_cell_day_frp.parquet";
    static final String META = "m2v_meta.json";
    static final ReleaseManifestCheck CHECK = new ReleaseManifestCheck(new DuckDb(new EmberlineProperties(null, null, null, null, null)));

    @TempDir
    Path tmp;

    /** Every listed file, as if every one were read by a loader (the strictest reading of the check). */
    static List<String> listed(Path dir) throws IOException {
        List<String> lines = Files.readAllLines(dir.resolve(ReleaseManifestCheck.FILE));
        return lines.subList(1, lines.size()).stream().map(l -> l.split(",", -1)[0]).toList();
    }

    static ValidationReport check(Path dir) throws IOException {
        ValidationReport report = new ValidationReport();
        CHECK.check(dir, listed(FIXTURE), Fixtures::sha256, report);
        return report;
    }

    Path copy() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("release"));
        try (Stream<Path> files = Files.list(FIXTURE)) {
            for (Path f : files.toList()) {
                Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir;
    }

    /** Replaces field {@code index} (0 file, 1 rows, 2 bytes, 3 sha256) of {@code file}'s manifest line. */
    static void edit(Path dir, String file, int index, String value) throws IOException {
        Path csv = dir.resolve(ReleaseManifestCheck.FILE);
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(csv)) {
            String[] f = line.split(",", -1);
            if (f[0].equals(file)) {
                f[index] = value;
                line = String.join(",", f);
            }
            out.add(line);
        }
        Files.write(csv, out);
    }

    @Test
    void theFixturePassesItsManifest() throws IOException {
        assertThat(listed(FIXTURE)).hasSize(19).contains(FRP, "satellite_day_status.parquet", "satellite_month_status.parquet");
        ValidationReport report = check(FIXTURE);
        assertThat(report.reasons()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void aWrongSha256IsRefusedNamingTheFile() throws IOException {
        Path dir = copy();
        edit(dir, FRP, 3, "0".repeat(64));
        assertThat(check(dir).reasons()).containsExactly("MANIFEST_SHA256_MISMATCH:" + FRP);
    }

    @Test
    void aWrongByteCountIsRefusedNamingTheFile() throws IOException {
        Path dir = copy();
        edit(dir, "satellite_day_status.parquet", 2, "1");
        assertThat(check(dir).reasons()).containsExactly("MANIFEST_BYTES_MISMATCH:satellite_day_status.parquet:1!=764934");
    }

    @Test
    void aWrongRowCountIsRefusedNamingTheFile() throws IOException {
        Path dir = copy();
        edit(dir, FRP, 1, "281");
        assertThat(check(dir).reasons()).containsExactly("MANIFEST_ROWS_MISMATCH:" + FRP + ":281!=280");
    }

    @Test
    void aListedFileThatIsMissingIsRefusedNamingTheFile() throws IOException {
        Path dir = copy();
        Files.delete(dir.resolve("fwi_meta.json"));
        assertThat(check(dir).reasons()).containsExactly("MANIFEST_FILE_MISSING:fwi_meta.json");
    }

    @Test
    void anUnlistedExtraFileIsAWarningOnly() throws IOException {
        Path dir = copy();
        Files.writeString(dir.resolve("extra.txt"), "not in the manifest");
        Files.writeString(dir.resolve("SHA256SUMS"), "");
        Files.writeString(dir.resolve(".done"), "");
        ValidationReport report = check(dir);
        assertThat(report.reasons()).isEmpty();
        assertThat(report.warnings()).containsExactly("MANIFEST_UNLISTED:extra.txt");
    }

    @Test
    void aFileNoLoaderReadsIsOnlyCheckedForPresence() throws IOException {
        Path dir = copy();
        edit(dir, "fwi_meta.json", 3, "0".repeat(64));
        ValidationReport report = new ValidationReport();
        CHECK.check(dir, List.of(FRP), Fixtures::sha256, report);
        assertThat(report.reasons()).isEmpty();
    }

    /** The real v1.2 layout: the manifest lists {@code models/m2v_meta.json}; the folder has it there and at the top. */
    Path modelsLayout() throws IOException {
        Path dir = copy();
        edit(dir, META, 0, "models/" + META);
        Files.copy(dir.resolve(META), Files.createDirectory(dir.resolve("models")).resolve(META));
        return dir;
    }

    @Test
    void aTopLevelCopyOfASubdirectoryEntryThatMatchesPassesWithoutAWarning() throws IOException {
        Path dir = modelsLayout();
        assertThat(Files.readString(dir.resolve(ReleaseManifestCheck.FILE))).contains("\nmodels/m2v_meta.json,,18374,290e");
        ValidationReport report = check(dir);
        assertThat(report.reasons()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void aTopLevelCopyWithTheSameSizeButOtherBytesIsRefusedNamingTheTopLevelFile() throws IOException {
        Path dir = modelsLayout();
        String meta = Files.readString(dir.resolve(META));
        Files.writeString(dir.resolve(META), meta.replaceFirst("\"seed\": 42", "\"seed\": 43"));
        assertThat(Files.size(dir.resolve(META))).isEqualTo(18374);
        assertThat(check(dir).reasons()).containsExactly("MANIFEST_SHA256_MISMATCH:" + META);
    }

    @Test
    void aTopLevelCopyOfAnotherSizeIsRefusedNamingTheTopLevelFile() throws IOException {
        Path dir = modelsLayout();
        Files.writeString(dir.resolve(META), "{\"method_version\": \"v1-m2v-2026-11-14\"}");
        assertThat(check(dir).reasons()).containsExactly("MANIFEST_BYTES_MISMATCH:" + META + ":18374!=" + Files.size(dir.resolve(META)));
    }

    @Test
    void theSubdirectoryEntryItselfMustBeThere() throws IOException {
        Path dir = modelsLayout();
        Files.delete(dir.resolve("models").resolve(META));
        assertThat(check(dir).reasons()).containsExactly("MANIFEST_FILE_MISSING:models/" + META);
    }

    @Test
    void aFileNoLoaderReadsIsNotMatchedByBaseName() throws IOException {
        Path dir = modelsLayout();
        Files.writeString(dir.resolve(META), "{}");
        ValidationReport report = new ValidationReport();
        CHECK.check(dir, List.of(FRP), Fixtures::sha256, report);
        assertThat(report.reasons()).isEmpty();
        assertThat(report.warnings()).containsExactly("MANIFEST_UNLISTED:" + META);
    }

    @Test
    void aMalformedManifestIsRefused() throws IOException {
        Path dir = copy();
        edit(dir, FRP, 0, "../" + FRP);
        assertThat(check(dir).reasons()).hasSize(1).allMatch(r -> r.startsWith("MANIFEST_INVALID:release_manifest.csv:"));
    }

    @Test
    void aReleaseWithoutTheManifestIsNotCheckedHere() throws IOException {
        Path dir = copy();
        Files.delete(dir.resolve(ReleaseManifestCheck.FILE));
        ValidationReport report = check(dir);
        assertThat(report.reasons()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }
}
