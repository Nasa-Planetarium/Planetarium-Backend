package io.emberline.acceptance.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.harmonized.domain.Reading;
import io.emberline.harmonized.domain.cases.ReadCase;
import io.emberline.harmonized.domain.cases.ReadCaseRegistry;
import io.emberline.shared.web.alias.HandoffAliasRule;
import io.emberline.shared.web.alias.HandoffAliasTable;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** rules without the application: data-case handlers, alias rewrites. */
class ReadUnitTest {

    @Test
    void everyReadCaseHasItsOwnHandler() {
        for (ReadCase c : ReadCase.values()) {
            assertThat(ReadCaseRegistry.handler(c)).as("handler for %s", c).isNotNull();
            assertThat(ReadCaseRegistry.handler(c).handles()).isEqualTo(c);
        }
        assertThat(java.util.Arrays.stream(ReadCase.values()).map(c -> ReadCaseRegistry.handler(c).getClass()).distinct())
                .hasSize(ReadCase.values().length);
    }

    @Test
    void missingMonthIsNullNeverZero() {
        Reading r = ReadCaseRegistry.apply(Reading.of(5.0, 1.0, 9.0, "missing").withVerdict("uncertain", false));
        assertThat(r.value()).isNull();
        assertThat(r.valueLo()).isNull();
        assertThat(r.valueHi()).isNull();
        assertThat(r.status()).isEqualTo("missing");
        assertThat(r.flags()).isEmpty();
    }

    @Test
    void valueOutsideIntervalKeepsValueAndStoredInterval() {
        Reading r = ReadCaseRegistry.apply(Reading.of(10.0, 2.0, 8.0, "ok"));
        assertThat(r.value()).isEqualTo(10.0);
        assertThat(r.valueLo()).isEqualTo(2.0);
        assertThat(r.valueHi()).isEqualTo(8.0);
        assertThat(r.flags()).containsExactly("interval_out_of_range");
        Reading below = ReadCaseRegistry.apply(Reading.of(1.0, 2.0, 8.0, "ok"));
        assertThat(below.value()).isEqualTo(1.0);
        assertThat(below.flags()).containsExactly("interval_out_of_range");
        Reading inside = ReadCaseRegistry.apply(Reading.of(5.0, 2.0, 8.0, "ok"));
        assertThat(inside.valueLo()).isEqualTo(2.0);
        assertThat(inside.flags()).isEmpty();
        Reading noise = ReadCaseRegistry.apply(Reading.of(100.0 + 1e-12, 100.0, 100.0, "ok"));
        assertThat(noise.flags()).as("summation noise is not out of range").isEmpty();
    }

    @Test
    void terraOnlyNullIntervalStaysNullAndIsFlagged() {
        Reading r = ReadCaseRegistry.apply(Reading.of(3.5, null, null, "ok"));
        assertThat(r.value()).isEqualTo(3.5);
        assertThat(r.valueLo()).isNull();
        assertThat(r.valueHi()).isNull();
        assertThat(r.flags()).containsExactly("interval_unavailable");
    }

    @Test
    void lowSampleKeepsTheStoredVerdict() {
        Reading r = ReadCaseRegistry.apply(Reading.of(3.118005613289087, 3.118005613289087, 3.118005613289087, "ok")
                .withVerdict("above", true));
        assertThat(r.verdict()).isEqualTo("above");
        assertThat(r.lowSample()).isTrue();
    }

    @Test
    void partialMonthIsUncertainAndKeepsProjection() {
        Reading r = ReadCaseRegistry.apply(Reading.of(44.336265563964844, 44.336265563964844, 44.336265563964844, "partial")
                .withProjection(102.31446064148237, LocalDate.of(2026, 11, 13)).withVerdict("below", false));
        assertThat(r.value()).isEqualTo(44.336265563964844);
        assertThat(r.valueProjected()).isEqualTo(102.31446064148237);
        assertThat(r.asOfDate()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(r.verdict()).isEqualTo("uncertain");
    }

    @Test
    void compareAliasBuildsYearMonth() {
        HandoffAliasRule rule = new HandoffAliasTable().find("/api/compare").orElseThrow();
        HandoffAliasRule.Canonical c = rule.rewrite(Map.of("aoi_id", new String[] {"KR"}, "year", new String[] {"2025"},
                "month", new String[] {"3"}), "aoi_id=KR&year=2025&month=3");
        assertThat(c.path()).isEqualTo("/api/v1/aois/KR/months/2025-03/comparison");
        assertThat(c.params()).isEmpty();
    }

    @Test
    void exportAliasDispatchesByTypeAndAsksForCsv() {
        HandoffAliasRule rule = new HandoffAliasTable().find("/api/export.csv").orElseThrow();
        Map<String, String[]> params = new java.util.LinkedHashMap<>();
        params.put("type", new String[] {"calendar"});
        params.put("aoi_id", new String[] {"KR"});
        params.put("start_year", new String[] {"2024"});
        HandoffAliasRule.Canonical c = rule.rewrite(params, null);
        assertThat(c.path()).isEqualTo("/api/v1/aois/KR/calendar");
        assertThat(c.query()).isEqualTo("start_year=2024&format=csv");
        params.put("type", new String[] {"nope"});
        assertThat(rule.rewrite(params, null).path()).isEqualTo("/api/v1/unknown-alias-type");
        // pl-fix2: type=hotspots → /api/v1/hotspots with the same parameters (no path parameter) and format=csv
        Map<String, String[]> hotspots = new java.util.LinkedHashMap<>();
        hotspots.put("type", new String[] {"hotspots"});
        hotspots.put("bbox", new String[] {"124,33,132,39"});
        hotspots.put("start", new String[] {"2025-03-01"});
        hotspots.put("end", new String[] {"2025-03-02"});
        hotspots.put("limit", new String[] {"5"});
        HandoffAliasRule.Canonical h = rule.rewrite(hotspots, null);
        assertThat(h.path()).isEqualTo("/api/v1/hotspots");
        assertThat(h.query()).isEqualTo("bbox=124%2C33%2C132%2C39&start=2025-03-01&end=2025-03-02&limit=5&format=csv");
        assertThat(h.params()).containsOnlyKeys("bbox", "start", "end", "limit", "format");
    }
}
