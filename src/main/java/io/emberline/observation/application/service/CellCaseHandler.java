package io.emberline.observation.application.service;

import io.emberline.observation.application.port.in.CellCollection.CellProperties;
import io.emberline.observation.domain.CellCase;
import io.emberline.observation.domain.CellRow;

/** One data case of a cell row (E4: a class per case, picked by {@link CellCaseRegistry}). */
interface CellCaseHandler {

    CellCase handles();

    /** Whether the row is this case; the registry asks handlers in {@link CellCase} declaration order. */
    boolean applies(CellRow row);

    CellProperties properties(CellRow row);

    /**
     * Properties with the file's values (A1) and the given flags; event columns are not in the release.
     * value_projected / as_of_date only on partial rows (absent, not null, on every other row).
     */
    static CellProperties of(CellRow r, boolean outOfRange, boolean unavailable) {
        boolean partial = r.partial();
        return new CellProperties(r.cellId(), asWritten(r.cellLat()), asWritten(r.cellLon()), r.value(), r.valueLo(), r.valueHi(), r.rawModis(),
                r.rawViirs(), r.valueSource(), r.dataSource(), r.status(), partial ? r.valueProjected() : null,
                partial ? r.asOfDate() : null, outOfRange, unavailable,
                null, null, null, null, null, null, null, null);
    }

    /** A float32 file value as its shortest decimal (36.65, not 36.650001525878906). */
    static double asWritten(float v) {
        return Double.parseDouble(Float.toString(v));
    }
}
