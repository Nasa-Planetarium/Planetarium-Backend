package io.emberline.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.outlook.domain.AoiRanking;
import io.emberline.outlook.domain.AoiRow;
import io.emberline.outlook.domain.Bbox;
import io.emberline.outlook.domain.DisplayRound;
import io.emberline.outlook.domain.GridCell;
import io.emberline.outlook.domain.GridSummaryStats;
import io.emberline.outlook.domain.VsNormal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** rules without Spring: vs_normal boundary (P1-9), rank, grid summary, bbox, display rounding. */
class OutlookDomainTest {

    @Test
    void p1_9_vsNormalBoundaryRowsUseTheStoredDoubles() {
        // differences of exactly ±0.10 in binary: 0.2 − 0.1 == 0.1 and 0.1 − 0.2 == −0.1 as doubles
        assertThat(0.2 - 0.1).isEqualTo(0.1);
        assertThat(0.1 - 0.2).isEqualTo(-0.1);
        assertThat(VsNormal.of(0.2, 0.1)).isEqualTo(VsNormal.HIGHER);
        assertThat(VsNormal.of(0.1, 0.0)).isEqualTo(VsNormal.HIGHER);
        assertThat(VsNormal.of(0.1, 0.2)).isEqualTo(VsNormal.LOWER);
        assertThat(VsNormal.of(0.0, 0.1)).isEqualTo(VsNormal.LOWER);
        // 0.0999…: 0.35 − 0.25 == 0.09999999999999998 in binary, so similar although the decimals differ by 0.10
        assertThat(0.35 - 0.25).isLessThan(0.1);
        assertThat(VsNormal.of(0.35, 0.25)).isEqualTo(VsNormal.SIMILAR);
        assertThat(VsNormal.of(0.25, 0.35)).isEqualTo(VsNormal.SIMILAR);
        assertThat(VsNormal.of(0.0999999, 0.0)).isEqualTo(VsNormal.SIMILAR);
        assertThat(VsNormal.of(0.0, 0.0999999)).isEqualTo(VsNormal.SIMILAR);
        // file float32 values widened exactly: 0.5f − 0.4f = 0.0999999940… (similar), 0.3f − 0.2f = 0.1000000089… (higher)
        assertThat(VsNormal.of(0.5f, 0.4f)).isEqualTo(VsNormal.SIMILAR);
        assertThat(VsNormal.of(0.3f, 0.2f)).isEqualTo(VsNormal.HIGHER);
        assertThat(VsNormal.HIGHER.code()).isEqualTo("higher");
        assertThat(VsNormal.SIMILAR.code()).isEqualTo("similar");
        assertThat(VsNormal.LOWER.code()).isEqualTo("lower");
    }

    static AoiRow row(String id, double p, boolean suspected) {
        return new AoiRow(id, "2026-12", p, 1 - p, 0.1, "m", "2026-11-14", suspected, null, 14, true);
    }

    @Test
    void rankSkipsSuspectedRowsBreaksTiesByAoiIdAndSharesNoRank() {
        List<AoiRanking.Ranked> ranked = AoiRanking.rank(List.of(row("SGG_3", 0.5, false), row("SGG_1", 0.9, true),
                row("SGG_2", 0.5, false), row("SGG_4", 0.7, false), row("SGG_5", 0.95, true)));
        assertThat(ranked).extracting(r -> r.row().aoiId() + "=" + r.rank())
                .containsExactly("SGG_4=1", "SGG_2=2", "SGG_3=3", "SGG_5=null", "SGG_1=null");
    }

    static GridCell cell(double p, boolean persistent) {
        return new GridCell(1, 0.25, 0.25, p, 1 - p, 0.0, persistent, "KOR", "Asia", 0.0, true);
    }

    @Test
    void gridSummaryExcludesPersistentCellsAndTakesTheLowerMiddleMedian() {
        GridSummaryStats s = GridSummaryStats.of(List.of(cell(0.05, false), cell(0.1, false), cell(0.4, false),
                cell(0.75, false), cell(0.99, true)));
        assertThat(s.nCells()).isEqualTo(4);
        assertThat(s.nPersistent()).isEqualTo(1);
        assertThat(List.of(s.lt10(), s.p10to40(), s.p40to70(), s.ge70())).containsExactly(1, 1, 1, 1);
        assertThat(s.pAnyMax()).isEqualTo(0.75);
        assertThat(s.pAnyMedian()).isEqualTo(0.1);
        GridSummaryStats odd = GridSummaryStats.of(List.of(cell(0.3, false), cell(0.1, false), cell(0.2, false)));
        assertThat(odd.pAnyMedian()).isEqualTo(0.2);
        GridSummaryStats onlyPersistent = GridSummaryStats.of(List.of(cell(0.5, true)));
        assertThat(onlyPersistent.nCells()).isZero();
        assertThat(onlyPersistent.pAnyMax()).isNull();
        assertThat(onlyPersistent.pAnyMedian()).isNull();
    }

    @Test
    void bboxContainsCentresAndWrapsTheAntimeridian() {
        Bbox box = new Bbox(-68.5, -55, -67.5, -54.5);
        assertThat(box.areaDeg2()).isEqualTo(0.5);
        assertThat(box.contains(-54.75, -68.25)).isTrue();
        assertThat(box.contains(-54.75, -68.75)).isFalse();
        Bbox wrap = new Bbox(170, -10, -170, 10);
        assertThat(wrap.crossesAntimeridian()).isTrue();
        assertThat(wrap.areaDeg2()).isEqualTo(400.0);
        assertThat(wrap.contains(0.25, 179.75)).isTrue();
        assertThat(wrap.contains(0.25, -179.75)).isTrue();
        assertThat(wrap.contains(0.25, 0.25)).isFalse();
    }

    @Test
    void displayRoundingIsHalfUpOnTheExactDouble() {
        assertThat(DisplayRound.round(0.011730000004172325, DisplayRound.FULL)).isEqualTo(0.01173);
        assertThat(DisplayRound.round(0.42857143, DisplayRound.FULL)).isEqualTo(0.42857);
        assertThat(DisplayRound.round(0.98827, DisplayRound.MAP)).isEqualTo(0.9883);
        assertThat(DisplayRound.round((Double) null, DisplayRound.FULL)).isNull();
        assertThat(DisplayRound.round(-54.75, DisplayRound.MAP)).isEqualTo(-54.75);
    }
}
