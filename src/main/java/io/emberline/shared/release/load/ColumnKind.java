package io.emberline.shared.release.load;

import java.util.Set;
import java.util.function.Predicate;

/** Accepted DuckDB column types per logical kind (an all-NULL column is accepted as any kind). */
public enum ColumnKind {
    TEXT(t -> t.equals("VARCHAR")),
    INTEGER(t -> Set.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "HUGEINT").contains(t)),
    NUMBER(t -> INTEGER.accepts(t) || Set.of("FLOAT", "DOUBLE").contains(t) || t.startsWith("DECIMAL")),
    BOOLEAN(t -> t.equals("BOOLEAN")),
    DATE(t -> t.equals("DATE") || t.startsWith("TIMESTAMP")),
    TIMESTAMP(t -> t.startsWith("TIMESTAMP"));

    private final Predicate<String> accepts;

    ColumnKind(Predicate<String> accepts) {
        this.accepts = accepts;
    }

    public boolean accepts(String duckType) {
        return duckType.equals("NULL") || accepts.test(duckType);
    }
}
