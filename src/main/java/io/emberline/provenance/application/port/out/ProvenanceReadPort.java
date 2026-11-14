package io.emberline.provenance.application.port.out;

import io.emberline.provenance.domain.MethodMeta;
import io.emberline.provenance.domain.MonthStatus;
import io.emberline.provenance.domain.SatelliteDay;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** provenance partitions of one release. */
public interface ProvenanceReadPort {

    Optional<MethodMeta> methodMeta(long releaseId);

    List<MonthStatus> monthStatus(long releaseId);

    List<SatelliteDay> satelliteDays(long releaseId, LocalDate start, LocalDate end);

    boolean hasSatelliteDays(long releaseId);
}
