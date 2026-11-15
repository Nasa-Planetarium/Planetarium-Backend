package io.emberline.shared.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * "No data here" of a v1.2 layer (contract schema {@code Coverage}, MAPPING §8.3): not an error but 200 with an empty
 * array or null value plus this object. {@code available=false} carries a reason; {@code available=true} with an
 * empty array means "data, but nothing in range". {@code reason} is always written (null when available);
 * {@code regions} only when set.
 *
 * @param available whether the layer has data for the asked area
 * @param scope     the range the layer has data for
 * @param reason    why not, only when {@code available=false}
 * @param regions   regions the layer covers, or null
 */
public record Coverage(boolean available, Scope scope, @JsonInclude(JsonInclude.Include.ALWAYS) Reason reason,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<Region> regions) {

    public Coverage {
        Objects.requireNonNull(scope);
        if (available == (reason != null)) {
            throw new IllegalArgumentException("coverage reason is set exactly when available=false");
        }
        regions = regions == null ? null : List.copyOf(regions);
    }

    /** The layer has data for the asked area (the result may still be empty). */
    public static Coverage availableFor(Scope scope) {
        return new Coverage(true, scope, null, null);
    }

    /** The layer has no data for the asked area: the response carries no values. */
    public static Coverage unavailable(Scope scope, Reason reason) {
        return new Coverage(false, scope, Objects.requireNonNull(reason), null);
    }

    public Coverage withRegions(Region... regions) {
        return new Coverage(available, scope, reason, Arrays.asList(regions));
    }

    /** Contract enum {@code Coverage.scope}. */
    public enum Scope {
        WORLD_LAND_DETECTED("world_land_detected"), KR_SGG("kr_sgg"), KR_ARG_EVENTS("kr_arg_events"),
        KR_ARG_FWI("kr_arg_fwi"), KR_BOX_ARG_SPREAD("kr_box_arg_spread"), KR_ARG_REGIME("kr_arg_regime"),
        WORLD_FRP("world_frp");

        private final String code;

        Scope(String code) {
            this.code = code;
        }

        @JsonValue
        public String code() {
            return code;
        }
    }

    /** Contract enum {@code Coverage.reason}; {@code NOT_SGG_LEVEL} is reserved (answered with grid_summary). */
    public enum Reason {
        OUT_OF_COVERAGE("out_of_coverage"), NO_CELLS("no_cells"), NOT_SGG_LEVEL("not_sgg_level");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        @JsonValue
        public String code() {
            return code;
        }
    }

    /** Contract enum {@code Coverage.regions[]}. */
    public enum Region {
        WORLD("WORLD"), KR("KR"), KR_BOX("KR_BOX"), ARG("ARG");

        private final String code;

        Region(String code) {
            this.code = code;
        }

        @JsonValue
        public String code() {
            return code;
        }
    }
}
