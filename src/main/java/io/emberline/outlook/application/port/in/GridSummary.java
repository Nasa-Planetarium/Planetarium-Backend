package io.emberline.outlook.application.port.in;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Contract {@code GridSummary} (no {@code top_cells}: optional, not served). {@code n_cells} and the bands exclude
 * persistent-source cells, which {@code n_persistent} counts; p_any max and median rounded to 5 decimals for display.
 */
public record GridSummary(int nCells, int nPersistent, Bands bands, Double pAnyMax, Double pAnyMedian) {

    /** Cell counts per screen colour step of p_any: &lt; 10 %, 10–40 %, 40–70 %, ≥ 70 %. */
    public record Bands(@JsonProperty("lt10") int lt10, @JsonProperty("p10_40") int p10to40,
                        @JsonProperty("p40_70") int p40to70, @JsonProperty("ge70") int ge70) {
    }
}
