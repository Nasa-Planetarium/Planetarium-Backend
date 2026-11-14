package io.emberline.shared.release.load;

import java.util.Map;

/** Rows written per table by one loader. */
public record LoadResult(Map<String, Long> rows) {

    public LoadResult {
        rows = Map.copyOf(rows);
    }
}
