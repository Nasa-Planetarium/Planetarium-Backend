package io.emberline.harmonized.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Range total: Σ of the known rows (+ the included month residual for freq=day), counts of missing / out-of-range rows. */
public record SummaryTotal(Double value, Double valueLo, Double valueHi, int nMissing, int nOutOfRange,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Double residualIncluded,
                           boolean residualProrated) {
}
