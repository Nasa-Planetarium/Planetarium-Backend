package io.emberline.external.domain;

import java.util.Objects;

/** The FIRMS MAP_KEY. Never printed: {@link #toString()} is masked; only the upstream adapter calls {@link #reveal()}. */
public final class FirmsKey {

    private final String value;

    public FirmsKey(String value) {
        this.value = Objects.requireNonNull(value);
    }

    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "FirmsKey(****)";
    }
}
