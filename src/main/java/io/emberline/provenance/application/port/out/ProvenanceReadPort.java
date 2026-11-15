package io.emberline.provenance.application.port.out;

import io.emberline.provenance.domain.MethodMeta;
import io.emberline.provenance.domain.SatelliteDay;
import io.emberline.provenance.domain.SatelliteMonth;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** provenance partitions of one release. */
public interface ProvenanceReadPort {

    Optional<MethodMeta> methodMeta(long releaseId);

    /** satellite_day rows with date in [start, end], by date then satellite. */
    List<SatelliteDay> satelliteDays(long releaseId, LocalDate start, LocalDate end);

    /** The last date_utc of satellite_day_status; empty when the release did not ship the file. */
    Optional<LocalDate> satelliteDayThrough(long releaseId);

    /** Every satellite_month row (empty when the release did not ship the file), by year, month, satellite. */
    List<SatelliteMonth> satelliteMonths(long releaseId);
}
