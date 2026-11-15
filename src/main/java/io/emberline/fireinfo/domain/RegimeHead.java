package io.emberline.fireinfo.domain;

/** What aoi_month_fire_regime holds for one AOI: its number of months (0 = absent) and its name. */
public record RegimeHead(long months, String name) {

    public boolean present() {
        return months > 0;
    }
}
