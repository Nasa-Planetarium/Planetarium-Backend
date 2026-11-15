package io.emberline.outlook.application.port.in;

import io.emberline.shared.json.Coverage;
import java.util.List;

/** Contract {@code AoiOccurrenceList}. */
public record AoiOccurrenceListResponse(String parentId, String targetMonth, String asOfDate, int count,
                                        List<AoiOccurrenceItem> items, Coverage coverage, String notice,
                                        String dataRelease) {
}
