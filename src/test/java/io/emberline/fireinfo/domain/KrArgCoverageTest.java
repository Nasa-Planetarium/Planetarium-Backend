package io.emberline.fireinfo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** range (unit): the five AOI shapes and the two judgement boxes of the contract. */
class KrArgCoverageTest {

    @Test
    void aoisInRange() {
        assertThat(KrArgCoverage.covers("KR")).isTrue();
        assertThat(KrArgCoverage.covers("SIDO_47")).isTrue();
        assertThat(KrArgCoverage.covers("SGG_47170")).isTrue();
        assertThat(KrArgCoverage.covers("ADM0_ARG")).isTrue();
        assertThat(KrArgCoverage.covers("ADM1_ARG-1271")).isTrue();
        assertThat(KrArgCoverage.covers("ADM0_PRY")).isFalse();
        assertThat(KrArgCoverage.covers("ADM0_JPN")).isFalse();
        assertThat(KrArgCoverage.covers("ADM1_PRY-1")).isFalse();
        assertThat(KrArgCoverage.covers("WORLD")).isFalse();
    }

    @Test
    void boxesOverlapIncludingEdges() {
        assertThat(KrArgCoverage.covers(new Bbox(132.0, 38.7, 133.0, 39.0))).as("touches the KR corner").isTrue();
        assertThat(KrArgCoverage.covers(new Bbox(132.01, 33.0, 135.0, 36.0))).isFalse();
        assertThat(KrArgCoverage.covers(new Bbox(-62.0, -26.5, -61.0, -25.5))).isTrue();
        assertThat(KrArgCoverage.covers(new Bbox(0.0, 40.0, 10.0, 50.0))).isFalse();
    }

    @Test
    void bboxParseAndArea() {
        assertThat(Bbox.parse("124.5,33,132,38.7")).contains(new Bbox(124.5, 33.0, 132.0, 38.7));
        assertThat(Bbox.parse("132,33,124.5,38.7")).isEmpty();
        assertThat(Bbox.parse("1,2,3")).isEmpty();
        assertThat(Bbox.parse("-181,0,1,1")).isEmpty();
        assertThat(new Bbox(120, 30, 141, 50).areaDeg2()).isEqualTo(420.0);
    }

    @Test
    void eventAreaRules() {
        assertThat(EventArea.of("KR", "대한민국")).contains(new EventArea(EventArea.Kind.COUNTRY, "KOR", null));
        assertThat(EventArea.of("ADM0_ARG", "Argentina")).contains(new EventArea(EventArea.Kind.COUNTRY, "ARG", null));
        assertThat(EventArea.of("ADM1_ARG-1271", "x")).contains(new EventArea(EventArea.Kind.ADM1, "ARG-1271", null));
        assertThat(EventArea.of("SIDO_47", "경상북도")).contains(new EventArea(EventArea.Kind.SIDO, "경상북도", null));
        assertThat(EventArea.of("SGG_47170", "안동시")).contains(new EventArea(EventArea.Kind.SGG, "SGG_47170", null));
        assertThat(EventArea.of("ADM0_PRY", "Paraguay")).isEmpty();
    }
}
