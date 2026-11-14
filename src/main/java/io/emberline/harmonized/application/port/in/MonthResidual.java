package io.emberline.harmonized.application.port.in;

import java.util.Map;

/**
 * freq=day only: the month residual (Σ harmonized_cell_day.residual_month of the AOI's cells, stored on day 1 but a
 * monthly amount) — never part of a day value. {@code residual_included} is what the range total adds:
 * month_residual × included_days / days_in_month ({@code residual_prorated} when the range cuts the month).
 */
public record MonthResidual(int year, int month, Double monthResidual, boolean residualProrated, int includedDays,
                            int daysInMonth, Double residualIncluded, Map<String, Double> byType, String note) {

    public static final String NOTE = "MODIS 기간 점으로 표시되지 않는 추정분, 월 단위";
}
