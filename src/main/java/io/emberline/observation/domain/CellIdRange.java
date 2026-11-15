package io.emberline.observation.domain;

/**
 * Grid rows and columns of a cell query, inclusive (row = cell_id / 3600, column = cell_id % 3600, see
 * {@link CellGrid}); empty when a bound is inverted. {@link #idLo()} … {@link #idHi()} bound the cell ids, so an index on
 * cell_id can skip what lies outside the rows.
 */
public record CellIdRange(long rowLo, long rowHi, long colLo, long colHi) {

    static final long LAST_ROW = 1799;
    static final long LAST_COL = 3599;

    /** Cells whose center lies in the box (the /cells rule). */
    public static CellIdRange of(CellBox box) {
        return new CellIdRange(Math.max(0, CellGrid.firstCenter(box.south()) + 900),
                Math.min(LAST_ROW, CellGrid.lastCenter(box.north()) + 900),
                Math.max(0, CellGrid.firstCenter(box.west()) + 1800),
                Math.min(LAST_COL, CellGrid.lastCenter(box.east()) + 1800));
    }

    /** The smallest range holding every cell of a non-empty set. */
    public static CellIdRange enclosing(long[] cellIds) {
        long rowLo = Long.MAX_VALUE;
        long rowHi = Long.MIN_VALUE;
        long colLo = Long.MAX_VALUE;
        long colHi = Long.MIN_VALUE;
        for (long id : cellIds) {
            rowLo = Math.min(rowLo, CellGrid.row(id));
            rowHi = Math.max(rowHi, CellGrid.row(id));
            colLo = Math.min(colLo, CellGrid.col(id));
            colHi = Math.max(colHi, CellGrid.col(id));
        }
        return new CellIdRange(rowLo, rowHi, colLo, colHi);
    }

    public boolean isEmpty() {
        return rowLo > rowHi || colLo > colHi;
    }

    public long idLo() {
        return CellGrid.id(rowLo, colLo);
    }

    public long idHi() {
        return CellGrid.id(rowHi, colHi);
    }
}
