package io.emberline.harmonized.domain;

/**
 * Σ harmonized_cell_day.residual_month of one month over a cell set: the total, and the part stored on the
 * background-prediction carrier rows (value_source {@code modis_m2v_background}; that part is not in
 * harmonized_residual_cell_month).
 */
public record ResidualSums(double total, double background) {
}
