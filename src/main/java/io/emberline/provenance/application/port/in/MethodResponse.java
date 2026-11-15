package io.emberline.provenance.application.port.in;

import io.emberline.shared.json.RawJson;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/methods/current (contract schema Method): meta = m2v_meta.json as-is; {@code display_names} (internal id →
 * screen name) and {@code performance_copy} as the contract's {@code info.x-display-names}.
 */
public record MethodResponse(String methodVersion, String unit, String unitCode, RawJson meta,
                             List<Map<String, Object>> valueSources, List<String> statuses, Map<String, Object> verdictRule,
                             List<Map<String, Object>> rules, List<Map<String, Object>> sources, String dataVersion,
                             Map<String, String> displayNames, String performanceCopy, RawJson files, Ingest ingest) {

    /** What ingest recorded for this release. */
    public record Ingest(String loadedAt, RawJson aliasesUsed, RawJson warnings) {
    }
}
