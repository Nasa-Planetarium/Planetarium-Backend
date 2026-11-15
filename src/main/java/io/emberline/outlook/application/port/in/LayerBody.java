package io.emberline.outlook.application.port.in;

import com.fasterxml.jackson.annotation.JsonValue;
import io.emberline.shared.json.RawJson;
import java.util.function.Supplier;

/**
 * A layer response in its two representations, built only when written: the JSON as one verbatim fragment (serialized
 * once per release and normalized query, then cached — contract CacheControlLayer) and, on the routes with
 * {@code format}, the CSV table. Input checks and data reads happen before the body is returned, so writing it never
 * fails on input; a 304 builds neither.
 *
 * @param csvRows rows the CSV has (checked against {@code Version.limits} csv_rows before anything is written)
 * @param json the JSON body
 * @param csv the CSV table, or null on a JSON-only route
 */
public record LayerBody(int csvRows, Supplier<RawJson> json, Supplier<OccurrenceCsv> csv) {

    @JsonValue
    public RawJson jsonValue() {
        return json.get();
    }
}
