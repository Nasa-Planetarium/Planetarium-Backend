package io.emberline.outlook.application.port.out;

import io.emberline.outlook.domain.AoiLayer;
import io.emberline.outlook.domain.GridLayer;
import java.util.Optional;

/** The outlook partitions of a release; empty when the release has no rows (file not shipped or not loaded). */
public interface OccurrenceReadPort {

    Optional<GridLayer> grid(long releaseId);

    Optional<AoiLayer> aois(long releaseId);
}
