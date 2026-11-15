package io.emberline.harmonized.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.harmonized.domain.MonthRecord;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The cached bundle (schema 2) keeps the v1.2 r3 compare fields through Redis JSON. */
class BundleJsonTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void schemaTwoRoundTripsBaselinePeriodAndContinuity() {
        // KR 2024-08 of data-v1.2-2026-11-15 r3 (aoi_month + aoi_month_compare as stored)
        MonthRecord kr = new MonthRecord(2024, 8, 96.0, 96.0, 96.0, 96.0, LocalDate.of(2024, 8, 31), 109L, 162L, "snpp_observed",
                "SP", "ok", 1123, 96.0, 52.809523809523796, 34.46666666666668, 69.0, 100.0, 1.8178539224526604, 13, "above", "ok",
                false, 96.0, LocalDate.of(2024, 8, 31), "2012-2025", 0.6814056609360736, "own");
        MonthRecord older = new MonthRecord(2024, 8, 1.0, 1.0, 1.0, null, null, null, null, null, null, "ok", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        RedisAoiMonthCache.Bundle b = new RedisAoiMonthCache.Bundle(CacheKeys.BUNDLE_SCHEMA, "KR", List.of(kr, older));
        RedisAoiMonthCache.Bundle back = JSON.readValue(JSON.writeValueAsString(b), RedisAoiMonthCache.Bundle.class);
        assertThat(back).isEqualTo(b);
        assertThat(back.months().get(0).continuitySource()).isEqualTo("own");
        assertThat(back.months().get(1).baselinePeriod()).isNull();
        assertThat(CacheKeys.BUNDLE_SCHEMA).isEqualTo(2);
    }
}
