package io.emberline.fireinfo.application.port.in;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.emberline.fireinfo.domain.SpreadCell;

/** Contract schema SpreadCell: the cell as stored plus the server's {@code level}. */
public record SpreadCellItem(String region, String candAdm0, double cellLat, double cellLon, double cellSizeDeg,
                             double pSpread, String level, boolean activeToday, Integer fireEventDays,
                             @JsonProperty("n_det_today_7x7") Integer nDetToday7x7) {

    public static SpreadCellItem of(SpreadCell c) {
        return new SpreadCellItem(c.region(), c.candAdm0(), c.cellLat(), c.cellLon(), c.cellSizeDeg(), c.pSpread(),
                c.level().code(), c.activeToday(), c.fireEventDays(), c.nDetToday7x7());
    }
}
