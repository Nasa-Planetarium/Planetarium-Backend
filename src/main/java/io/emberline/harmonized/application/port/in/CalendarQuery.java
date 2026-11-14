package io.emberline.harmonized.application.port.in;

/** Year range of a calendar request; null = the AOI's first / last year in the release. */
public record CalendarQuery(Integer startYear, Integer endYear) {
}
