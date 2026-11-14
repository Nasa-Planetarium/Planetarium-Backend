package io.emberline.provenance.domain;

/** Month status of the release (WORLD rows of aoi_month, values as the file has them). */
public record MonthStatus(int year, int month, String valueSource, String dataSource, String status) {

    public int key() {
        return year * 100 + month;
    }
}
