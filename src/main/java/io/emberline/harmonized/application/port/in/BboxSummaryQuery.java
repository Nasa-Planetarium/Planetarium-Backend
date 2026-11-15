package io.emberline.harmonized.application.port.in;

/** bbox text ({@code west,south,east,north}, checked by the use case) and the summary's range and frequency. */
public record BboxSummaryQuery(String bbox, SummaryQuery range) {
}
