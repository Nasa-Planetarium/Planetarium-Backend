package io.emberline.ingest.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** STAGED → VALIDATED → LOADING → LOADED → ACTIVE → RETIRED, any non-active state → REJECTED; transitions as a table. */
public enum ReleaseState {
    STAGED, VALIDATED, LOADING, LOADED, ACTIVE, RETIRED, REJECTED;

    private static final Map<ReleaseState, Set<ReleaseState>> NEXT = new EnumMap<>(ReleaseState.class);

    static {
        NEXT.put(STAGED, EnumSet.of(STAGED, VALIDATED, REJECTED));
        NEXT.put(VALIDATED, EnumSet.of(STAGED, LOADING, REJECTED));
        NEXT.put(LOADING, EnumSet.of(LOADED, REJECTED));
        NEXT.put(LOADED, EnumSet.of(ACTIVE, REJECTED));
        NEXT.put(ACTIVE, EnumSet.of(RETIRED));
        NEXT.put(RETIRED, EnumSet.of(ACTIVE, REJECTED));
        NEXT.put(REJECTED, EnumSet.of(STAGED));
    }

    public boolean canMoveTo(ReleaseState next) {
        return NEXT.get(this).contains(next);
    }

    /** States whose partitions are complete (activation or rollback target). */
    public boolean isLoaded() {
        return this == LOADED || this == ACTIVE || this == RETIRED;
    }
}
