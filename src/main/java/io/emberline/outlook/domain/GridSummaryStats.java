package io.emberline.outlook.domain;

import java.util.Arrays;
import java.util.List;

/**
 * Summary of the 0.5° cells of an AOI (contract schema {@code GridSummary}) — not a regional probability (A1: no
 * combining of cell probabilities). Persistent-source cells are counted in {@code nPersistent} only; every other
 * number is over the remaining cells: {@code nCells} = their count = the sum of the bands (screen colour steps
 * &lt; 10 %, 10–40 %, 40–70 %, ≥ 70 % of p_any), the maximum and the median p_any. The median of an even count is the
 * lower of the two middle values (no averaging, so it is always a file value). Max and median are null without cells.
 */
public record GridSummaryStats(int nCells, int nPersistent, int lt10, int p10to40, int p40to70, int ge70,
                               Double pAnyMax, Double pAnyMedian) {

    public static final double BAND_10 = 0.10;
    public static final double BAND_40 = 0.40;
    public static final double BAND_70 = 0.70;

    public static GridSummaryStats of(List<GridCell> cellsInside) {
        int persistent = 0;
        int lt10 = 0;
        int p10to40 = 0;
        int p40to70 = 0;
        int ge70 = 0;
        double[] values = new double[cellsInside.size()];
        int n = 0;
        for (GridCell c : cellsInside) {
            if (c.persistentSource()) {
                persistent++;
                continue;
            }
            double p = c.pAny();
            values[n++] = p;
            if (p < BAND_10) {
                lt10++;
            } else if (p < BAND_40) {
                p10to40++;
            } else if (p < BAND_70) {
                p40to70++;
            } else {
                ge70++;
            }
        }
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        Double max = n == 0 ? null : sorted[n - 1];
        Double median = n == 0 ? null : sorted[(n - 1) / 2];
        return new GridSummaryStats(n, persistent, lt10, p10to40, p40to70, ge70, max, median);
    }
}
