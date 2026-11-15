package io.emberline.observation.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.observation.application.port.in.CellCollection.CellProperties;
import io.emberline.observation.domain.CellRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * the read-path interval flags of a cell. The flags describe the
 * row; value, value_lo and value_hi stay as stored — an inverted interval is neither swapped nor widened.
 */
class CellCaseRegistryTest {

    static final CellCaseRegistry REGISTRY = new CellCaseRegistry(List.of(new RegularCellHandler(),
            new OutsideIntervalHandler(), new IntervalUnavailableHandler(), new MissingValueHandler()));

    static CellRow row(Double value, Double lo, Double hi, String valueSource, String status) {
        return new CellRow(4549889L, 36.35f, 128.95f, value, lo, hi, 1, 0, valueSource, "SP", status, null, null);
    }

    @Test
    void anInvertedIntervalIsServedAsStoredAndFlaggedOutOfRange() {
        // value_lo > value_hi (8,162 cell rows, rounding of the industrial-source removal ≤ 1.7e-5)
        CellProperties p = REGISTRY.properties(row(0.5, 0.500017, 0.5, "modis_m2v", "ok"));
        assertThat(p.value()).isEqualTo(0.5);
        assertThat(p.valueLo()).isEqualTo(0.500017);
        assertThat(p.valueHi()).isEqualTo(0.5);
        assertThat(p.intervalOutOfRange()).isTrue();
        assertThat(p.intervalUnavailable()).isFalse();
    }

    @Test
    void aMissingIntervalIsServedAsNullAndFlaggedUnavailable() {
        // modis_m2v_terra rows: value present, value_lo / value_hi null (meta cell_interval)
        CellProperties p = REGISTRY.properties(row(2.75, null, null, "modis_m2v_terra", "ok"));
        assertThat(p.value()).isEqualTo(2.75);
        assertThat(p.valueLo()).isNull();
        assertThat(p.valueHi()).isNull();
        assertThat(p.intervalOutOfRange()).isFalse();
        assertThat(p.intervalUnavailable()).isTrue();
    }

    @Test
    void aMissingMonthKeepsNullValuesAndHasNoInterval() {
        CellProperties p = REGISTRY.properties(row(null, null, null, "modis_m2v", "missing"));
        assertThat(p.value()).isNull();
        assertThat(p.intervalOutOfRange()).isFalse();
        assertThat(p.intervalUnavailable()).isTrue();
    }

    @Test
    void aValueInsideItsIntervalHasNoFlag() {
        CellProperties p = REGISTRY.properties(row(3.0, 2.5, 3.5, "modis_m2v", "ok"));
        assertThat(p.intervalOutOfRange()).isFalse();
        assertThat(p.intervalUnavailable()).isFalse();
    }
}
