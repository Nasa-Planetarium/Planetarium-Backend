package io.emberline.fireinfo.application.port.in;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.emberline.fireinfo.domain.RegimeMonth;

/** Contract schema FireRegimeMonth: one AOI-month as stored ({@code W}, {@code W_large} keep the file's names). */
public record FireRegimeMonthItem(int year, int month, String fireRegime, @JsonProperty("W") Double w,
                                  @JsonProperty("W_large") Double wLarge, Double shareLarge, Integer nEvents,
                                  Integer nLargeEvents, String topEventId, Double topEventW) {

    public static FireRegimeMonthItem of(RegimeMonth m) {
        return new FireRegimeMonthItem(m.year(), m.month(), m.fireRegime(), m.w(), m.wLarge(), m.shareLarge(), m.nEvents(),
                m.nLargeEvents(), m.topEventId(), m.topEventW());
    }
}
