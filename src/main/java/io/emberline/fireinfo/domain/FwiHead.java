package io.emberline.fireinfo.domain;

import java.time.LocalDate;

/** What fwi_aoi_day holds for one AOI: its last date ({@code as_of_date}), its name and its number of days (0 = absent). */
public record FwiHead(LocalDate asOfDate, String name, long days) {

    public boolean present() {
        return days > 0;
    }
}
