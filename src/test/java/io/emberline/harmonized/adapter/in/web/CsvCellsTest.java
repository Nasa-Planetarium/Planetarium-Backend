package io.emberline.harmonized.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** B2.08 cell rules: formula guard on text cells only; numbers unchanged; RFC 4180 quoting. */
class CsvCellsTest {

    @Test
    void textStartingWithAFormulaCharacterIsGuarded() {
        assertThat(CsvCells.cell("=1+2")).isEqualTo("'=1+2");
        assertThat(CsvCells.cell("+82")).isEqualTo("'+82");
        assertThat(CsvCells.cell("-x")).isEqualTo("'-x");
        assertThat(CsvCells.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvCells.cell("\tcmd")).isEqualTo("'\tcmd");
        assertThat(CsvCells.cell("\rcmd")).isEqualTo("\"'\rcmd\"");
        assertThat(CsvCells.cell("서울특별시")).isEqualTo("서울특별시");
    }

    @Test
    void numbersAreWrittenAsNumbers() {
        assertThat(CsvCells.cell(-3.5)).isEqualTo("-3.5");
        assertThat(CsvCells.cell(2764.0)).isEqualTo("2764.0");
        assertThat(CsvCells.cell(44.336265563964844)).isEqualTo("44.336265563964844");
        assertThat(CsvCells.cell(1.0e-5)).isEqualTo("0.000010");
        assertThat(CsvCells.cell(-7L)).isEqualTo("-7");
        assertThat(CsvCells.cell(null)).isEmpty();
        assertThat(CsvCells.cell(LocalDate.of(2026, 11, 13))).isEqualTo("2026-11-13");
    }

    @Test
    void quotingAndLists() {
        assertThat(CsvCells.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvCells.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvCells.cell(List.of("interval_out_of_range", "x"))).isEqualTo("interval_out_of_range;x");
        assertThat(CsvCells.cell(List.of("=a"))).isEqualTo("'=a");
    }
}
