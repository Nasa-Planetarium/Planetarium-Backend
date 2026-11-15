package io.emberline.shared.web.alias;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P2-10 (unit): /api/events|fwi|spread|regime take aoi or aoi_id; both → 40003. */
class AoiParamAliasTest {

    final HandoffAliasTable table = new HandoffAliasTable();

    static Map<String, String[]> params(String... kv) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], new String[] {kv[i + 1]});
        }
        return m;
    }

    @Test
    void aoiBecomesAoiIdOnTheQueryRoutes() {
        HandoffAliasRule.Canonical c = table.find("/api/events").orElseThrow()
                .rewrite(params("aoi", "KR", "start", "2025-03-01", "end", "2025-03-31"), "aoi=KR&start=2025-03-01&end=2025-03-31");
        assertThat(c.path()).isEqualTo("/api/v1/fire-events");
        assertThat(c.params()).containsOnlyKeys("aoi_id", "start", "end");
        assertThat(c.params().get("aoi_id")).containsExactly("KR");
        assertThat(c.query()).isEqualTo("aoi_id=KR&start=2025-03-01&end=2025-03-31");
        HandoffAliasRule.Canonical same = table.find("/api/events").orElseThrow()
                .rewrite(params("aoi_id", "KR", "start", "2025-03-01", "end", "2025-03-31"), "aoi_id=KR&start=2025-03-01&end=2025-03-31");
        assertThat(same.path()).isEqualTo("/api/v1/fire-events");
        assertThat(same.query()).isEqualTo("aoi_id=KR&start=2025-03-01&end=2025-03-31");
        assertThat(table.find("/api/spread").orElseThrow().rewrite(params("bbox", "124.5,33,132,38.7"), "bbox=124.5,33,132,38.7").path())
                .isEqualTo("/api/v1/spread-nowcast");
    }

    @Test
    void aoiBecomesThePathOnTheAoiRoutes() {
        assertThat(table.find("/api/fwi").orElseThrow().rewrite(params("aoi", "SIDO_47", "date", "2026-11-11"), "aoi=SIDO_47&date=2026-11-11").link())
                .isEqualTo("/api/v1/aois/SIDO_47/fwi?date=2026-11-11");
        assertThat(table.find("/api/fwi").orElseThrow().rewrite(params("aoi_id", "KR"), "aoi_id=KR").path())
                .isEqualTo("/api/v1/aois/KR/fwi");
        assertThat(table.find("/api/regime").orElseThrow().rewrite(params("aoi", "ADM0_ARG"), "aoi=ADM0_ARG").path())
                .isEqualTo("/api/v1/aois/ADM0_ARG/fire-regime");
    }

    @Test
    void bothNamesTogetherAre40003() {
        for (String path : new String[] {"/api/events", "/api/fwi", "/api/spread", "/api/regime"}) {
            assertThatThrownBy(() -> table.find(path).orElseThrow().rewrite(params("aoi", "KR", "aoi_id", "KR"), "aoi=KR&aoi_id=KR"))
                    .as(path)
                    .isInstanceOfSatisfying(InvalidRequestException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.AOI_AND_BBOX);
                        assertThat(e.errorCode().number()).isEqualTo(40003);
                    });
        }
    }
}
