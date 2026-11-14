package io.emberline.harmonized.adapter.out.parquet;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.harmonized.adapter.out.HarmonizedSettings;
import io.emberline.harmonized.domain.DayTotal;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.support.Duck;
import io.emberline.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Ruling R-CD1 row test on core-v11: (cell 4431110, 2026-03-01) has an observed row (value 2.0, residual NULL) and a
 * residual-carrier row (value 0, n_points 0, NULL bounds, residual_month 5.785714285714285). Both are summed.
 */
class CellDayParquetAdapterTest {

    static final ReleaseRef V11 = new ReleaseRef(1, "core-v11", "v1-m2v-2026-11-14", "core-v11#2af85d8061c2", Instant.EPOCH);
    static final ParquetReader READER = new ParquetReader(new HarmonizedSettings(null, null, null));
    static final ReleaseFiles FILES = new ReleaseFiles(new EmberlineProperties(null, null, null,
            new EmberlineProperties.Release(Fixtures.releaseRoot().toString(), null), null));

    @AfterAll
    static void close() throws Exception {
        READER.close();
    }

    @Test
    void splitDayOneKeyIsSummedNotDuplicatedOrDropped() {
        List<List<Object>> raw = Duck.query("SELECT value, n_points, residual_month FROM read_parquet(?) "
                + "WHERE cell_id = 4431110 AND date_local = DATE '2026-03-01' ORDER BY residual_month NULLS FIRST",
                V11Path.cellDay());
        assertThat(raw).as("the fixture key really repeats").hasSize(2);
        CellDayParquetAdapter adapter = new CellDayParquetAdapter(READER, FILES);
        List<DayTotal> days = adapter.dayTotals(V11, new long[] {4431110L}, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1));
        assertThat(days).hasSize(1);
        DayTotal d = days.get(0);
        assertThat(d.value()).isEqualTo(2.0);
        assertThat(d.valueLo()).isEqualTo(2.0);
        assertThat(d.valueHi()).isEqualTo(2.0);
        assertThat(d.nPoints()).isEqualTo(2);
        assertThat(d.valueSource()).isEqualTo("snpp_observed");
        Map<YearMonth, io.emberline.harmonized.domain.ResidualSums> residual = adapter.monthResiduals(V11, new long[] {4431110L}, YearMonth.of(2026, 3), YearMonth.of(2026, 3));
        assertThat(residual.get(YearMonth.of(2026, 3)).total()).isEqualTo(5.785714285714285);
    }

    @Test
    void residualByTypeComesFromTheResidualFile() {
        ResidualParquetAdapter adapter = new ResidualParquetAdapter(READER, FILES);
        assertThat(adapter.available(V11)).isTrue();
        List<List<Object>> expected = Duck.query("SELECT residual_type, sum(value) FROM read_parquet(?) "
                + "WHERE cell_id = 4431110 AND year = 2026 AND month = 3 GROUP BY 1", V11Path.residual());
        Map<YearMonth, Map<String, Double>> got = adapter.residualsByType(V11, new long[] {4431110L}, YearMonth.of(2026, 3), YearMonth.of(2026, 3));
        assertThat(got.get(YearMonth.of(2026, 3))).hasSize(expected.size());
        for (List<Object> row : expected) {
            assertThat(got.get(YearMonth.of(2026, 3)).get((String) row.get(0))).isEqualTo(((Number) row.get(1)).doubleValue());
        }
    }

    /** Paths of the owner fixture files. */
    static final class V11Path {
        static java.nio.file.Path cellDay() {
            return Fixtures.releaseRoot().resolve("core-v11/harmonized_cell_day.parquet");
        }

        static java.nio.file.Path residual() {
            return Fixtures.releaseRoot().resolve("core-v11/harmonized_residual_cell_month.parquet");
        }
    }
}
