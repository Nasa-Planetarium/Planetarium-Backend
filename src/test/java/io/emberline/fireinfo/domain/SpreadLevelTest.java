package io.emberline.fireinfo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P2-8 boundaries (unit): low &lt; 0.05 ≤ moderate ≤ 0.20 &lt; high. The loader stores a parquet FLOAT as
 * its shortest decimal (Float.toString), so the file values 0.05f and 0.2f arrive as 0.05 and 0.2.
 */
class SpreadLevelTest {

    @Test
    void boundariesAsTheContractStatesThem() {
        assertThat(SpreadLevel.of(Math.nextDown(0.05))).isEqualTo(SpreadLevel.LOW);
        assertThat(SpreadLevel.of(0.05)).isEqualTo(SpreadLevel.MODERATE);
        assertThat(SpreadLevel.of(0.20)).isEqualTo(SpreadLevel.MODERATE);
        assertThat(SpreadLevel.of(Math.nextUp(0.20))).isEqualTo(SpreadLevel.HIGH);
        assertThat(SpreadLevel.of(0.0)).isEqualTo(SpreadLevel.LOW);
        assertThat(SpreadLevel.of(1.0)).isEqualTo(SpreadLevel.HIGH);
    }

    @Test
    void storedFloatBoundariesStayOnTheirSide() {
        assertThat(SpreadLevel.of(Double.parseDouble(Float.toString(0.05f)))).isEqualTo(SpreadLevel.MODERATE);
        assertThat(SpreadLevel.of(Double.parseDouble(Float.toString(0.2f)))).isEqualTo(SpreadLevel.MODERATE);
        assertThat(SpreadLevel.of(Double.parseDouble(Float.toString(Math.nextUp(0.2f))))).isEqualTo(SpreadLevel.HIGH);
        assertThat(SpreadLevel.of(Double.parseDouble(Float.toString(Math.nextDown(0.05f))))).isEqualTo(SpreadLevel.LOW);
    }

    @Test
    void minLevelKeepsThatLevelAndAbove() {
        assertThat(SpreadLevel.HIGH.atLeast(SpreadLevel.MODERATE)).isTrue();
        assertThat(SpreadLevel.MODERATE.atLeast(SpreadLevel.MODERATE)).isTrue();
        assertThat(SpreadLevel.LOW.atLeast(SpreadLevel.MODERATE)).isFalse();
        assertThat(SpreadLevel.ofCode("high")).contains(SpreadLevel.HIGH);
        assertThat(SpreadLevel.ofCode("x")).isEmpty();
    }
}
