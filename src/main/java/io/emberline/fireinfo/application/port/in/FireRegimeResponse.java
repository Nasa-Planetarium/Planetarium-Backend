package io.emberline.fireinfo.application.port.in;

import io.emberline.shared.json.Coverage;
import java.util.List;

/** GET /api/v1/aois/{aoiId}/fire-regime (contract schema FireRegime); out of range: {@code months: []}, available=false. */
public record FireRegimeResponse(String aoiId, String name, List<FireRegimeMonthItem> months, Coverage coverage,
                                 String dataRelease) {

    public FireRegimeResponse {
        months = List.copyOf(months);
    }
}
