package io.emberline.harmonized.application.port.in;

/** One same-month year of the comparison baseline (aoi_month as stored; {@code used} = status ok with a value). */
public record BaselineYear(int year, Double value, String status, String valueSource, boolean used) {
}
