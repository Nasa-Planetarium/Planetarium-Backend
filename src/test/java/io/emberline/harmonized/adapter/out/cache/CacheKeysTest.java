package io.emberline.harmonized.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.shared.release.ReleaseRef;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CacheKeysTest {

    @Test
    void keyCarriesEnvSchemaMethodDataVersionAndAoi() {
        ReleaseRef r = new ReleaseRef(7, "core-v11", "v1-m2v-2026-11-14", "core-v11#2af85d8061c2", Instant.EPOCH);
        assertThat(CacheKeys.calendarBundle("dev", r, "KR")).isEqualTo("em:dev:c:calendar:s2:v1-m2v-2026-11-14:2af85d80:KR");
    }
}
