package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Case → handler map (E4: per-case classes and a registry instead of a switch). */
public final class DataCaseRegistry {

    private static final Map<DataCase, DataCaseHandler> HANDLERS = new EnumMap<>(DataCase.class);

    static {
        for (DataCaseHandler h : List.of(new KeepNullAsMissingHandler(), new KeepMissingWithValueHandler(),
                new KeepOutsideIntervalHandler(), new KeepNegativeValueHandler(),
                new KeepIntervalInvertedNoiseHandler(), new RejectRowsHandler(DataCase.NULL_VALUE_NOT_MISSING),
                new RejectRowsHandler(DataCase.INTERVAL_INVERTED), new RejectRowsHandler(DataCase.NEGATIVE_COUNT))) {
            HANDLERS.put(h.handles(), h);
        }
    }

    private DataCaseRegistry() {
    }

    public static DataCaseHandler handler(DataCase dataCase) {
        return HANDLERS.get(dataCase);
    }

    /** Applies the handlers for one counting row whose columns are named after the cases (lower case). */
    public static void apply(String file, ResultSet counts, ValidationReport report) throws SQLException {
        ResultSetMetaData md = counts.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            DataCase c = DataCase.valueOf(md.getColumnName(i).toUpperCase(Locale.ROOT));
            long rows = counts.getLong(i);
            if (rows > 0) {
                HANDLERS.get(c).handle(file, rows, report);
            }
        }
    }
}
