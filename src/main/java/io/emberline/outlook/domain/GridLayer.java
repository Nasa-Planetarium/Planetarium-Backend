package io.emberline.outlook.domain;

import java.util.List;

/**
 * The grid file of one release: its head (one target month, as-of date and model — the loader refuses more) and the
 * cells in cell_id order, with their centres as arrays for the AOI cell rule.
 */
public record GridLayer(String targetMonth, String asOfDate, String model, List<GridCell> cells) {

    public GridLayer {
        cells = List.copyOf(cells);
    }

    public double[] lats() {
        return cells.stream().mapToDouble(GridCell::lat).toArray();
    }

    public double[] lons() {
        return cells.stream().mapToDouble(GridCell::lon).toArray();
    }
}
