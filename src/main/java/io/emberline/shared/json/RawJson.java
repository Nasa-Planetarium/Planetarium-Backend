package io.emberline.shared.json;

import java.util.Objects;

/** A JSON fragment stored as-is (m2v_meta.json, a GeoJSON geometry) and written verbatim into responses. */
public record RawJson(String json) {

    public RawJson {
        Objects.requireNonNull(json);
    }
}
