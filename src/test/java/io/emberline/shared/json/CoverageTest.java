package io.emberline.shared.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emberline.shared.json.Coverage.Reason;
import io.emberline.shared.json.Coverage.Region;
import io.emberline.shared.json.Coverage.Scope;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Coverage serializes as contract schema Coverage — names and enum spellings exact. */
class CoverageTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void availableWritesANullReasonAndNoRegions() {
        String json = JSON.writeValueAsString(Coverage.availableFor(Scope.KR_ARG_FWI));
        assertThat(JSON.readTree(json)).isEqualTo(JSON.readTree("{\"available\":true,\"scope\":\"kr_arg_fwi\",\"reason\":null}"));
    }

    @Test
    void unavailableWritesTheReasonAndTheRegions() {
        String json = JSON.writeValueAsString(Coverage.unavailable(Scope.KR_ARG_EVENTS, Reason.OUT_OF_COVERAGE)
                .withRegions(Region.KR, Region.ARG));
        assertThat(JSON.readTree(json)).isEqualTo(JSON.readTree(
                "{\"available\":false,\"scope\":\"kr_arg_events\",\"reason\":\"out_of_coverage\",\"regions\":[\"KR\",\"ARG\"]}"));
    }

    @Test
    void enumSpellingsAreTheContractValues() {
        assertThat(Arrays.stream(Scope.values()).map(Scope::code)).containsExactly("world_land_detected", "kr_sgg",
                "kr_arg_events", "kr_arg_fwi", "kr_box_arg_spread", "kr_arg_regime", "world_frp");
        assertThat(Arrays.stream(Reason.values()).map(Reason::code)).containsExactly("out_of_coverage", "no_cells", "not_sgg_level");
        assertThat(Arrays.stream(Region.values()).map(Region::code)).containsExactly("WORLD", "KR", "KR_BOX", "ARG");
    }

    @Test
    void reasonIsSetExactlyWhenUnavailable() {
        assertThatThrownBy(() -> new Coverage(true, Scope.KR_SGG, Reason.NO_CELLS, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Coverage(false, Scope.KR_SGG, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
