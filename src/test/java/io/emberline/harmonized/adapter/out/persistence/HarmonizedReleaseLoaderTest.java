package io.emberline.harmonized.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.provenance.domain.MethodDescription;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.release.load.DuckDb;
import io.emberline.shared.release.load.ReleaseSource;
import io.emberline.shared.release.load.ValidationReport;
import io.emberline.support.Contract;
import io.emberline.support.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Ruling R-VS1: harmonized_cell_day.parquet accepts the cell_day-only value_source codes (the real v1.1 file has 156
 * rows modis_fallback_m2v_predicted, all on its NRT day 2026-11-14); any other unknown code still rejects the release.
 * The month files are the core-v11 owner fixture; cell_day is a few real rows with one value_source replaced.
 */
class HarmonizedReleaseLoaderTest {

    static final Path V11 = Fixtures.releaseRoot().resolve("core-v11");
    static final HarmonizedReleaseLoader LOADER = new HarmonizedReleaseLoader(
            new DuckDb(new EmberlineProperties(null, null, null, null, new EmberlineProperties.Ingest(1, "512MB", 1))), null);

    @Test
    @SuppressWarnings("unchecked")
    void cellDayCodesAreTheContractEnumPlusItsCellDayOnlyListAndMethodExplainsThem() {
        Map<String, Object> schema = Contract.at("components", "schemas", "ValueSource");
        Set<String> contract = new TreeSet<>((List<String>) schema.get("enum"));
        // Spec 272aedb dropped ValueSource.x-cell-day-only and its R-VS1 text; the list did not change, so the loader
        // (ruling R-VS1, 00-decisions E1 「the file is canonical」) holds it: its codes beyond the contract enum.
        Set<String> cellDayOnly = new TreeSet<>(HarmonizedReleaseLoader.CELL_DAY_SOURCES);
        cellDayOnly.removeAll(contract);
        assertThat(cellDayOnly).containsExactlyInAnyOrder("modis_m2v_background", "modis_fallback_m2v_predicted");
        assertThat(HarmonizedReleaseLoader.CELL_DAY_SOURCES).containsAll(contract);
        if (schema.get("x-cell-day-only") != null) {
            assertThat((List<String>) schema.get("x-cell-day-only")).containsExactlyInAnyOrderElementsOf(cellDayOnly);
        }
        contract.addAll(cellDayOnly);
        Set<String> method = new TreeSet<>();
        MethodDescription.VALUE_SOURCES.forEach(m -> method.add((String) m.get("value_source")));
        assertThat(method).isEqualTo(contract);
        MethodDescription.VALUE_SOURCES.stream().filter(m -> cellDayOnly.contains(m.get("value_source"))).forEach(m -> {
            assertThat((String) m.get("label_ko")).isNotBlank();
            assertThat(m.get("files")).isEqualTo(List.of("harmonized_cell_day"));
        });
    }

    @ParameterizedTest
    @CsvSource({
            "modis_m2v, true",
            "modis_m2v_background, true",
            "modis_fallback_m2v_predicted, true",
            "modis_fallback_m2v_bogus, false"})
    void cellDayValueSource(String code, boolean accepted, @TempDir Path dir) throws Exception {
        for (String f : List.of(HarmonizedReleaseLoader.AOI_MONTH, HarmonizedReleaseLoader.COMPARE, HarmonizedReleaseLoader.CELL_MONTH)) {
            Files.createSymbolicLink(dir.resolve(f), V11.resolve(f));
        }
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            c.createStatement().execute("COPY (SELECT * REPLACE (CASE WHEN row_number() OVER () = 1 THEN '" + code
                    + "' ELSE value_source END AS value_source) FROM (SELECT * FROM read_parquet('"
                    + V11.resolve(HarmonizedReleaseLoader.CELL_DAY) + "') WHERE value_source = 'modis_m2v' LIMIT 5)) TO '"
                    + dir.resolve(HarmonizedReleaseLoader.CELL_DAY) + "' (FORMAT parquet)");
        }
        ValidationReport report = new ValidationReport();
        LOADER.validate(new ReleaseSource(1, "rvs1", dir, "v1-m2v-2026-11-14", report));
        if (accepted) {
            assertThat(report.ok()).as(report.reasons().toString()).isTrue();
        } else {
            assertThat(report.ok()).isFalse();
            assertThat(report.reasons()).anyMatch(r -> r.startsWith("CODE_VALUE:" + HarmonizedReleaseLoader.CELL_DAY + ":value_source=" + code));
        }
    }
}
