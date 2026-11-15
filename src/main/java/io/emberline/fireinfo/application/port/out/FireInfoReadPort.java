package io.emberline.fireinfo.application.port.out;

import io.emberline.fireinfo.domain.Bbox;
import io.emberline.fireinfo.domain.EventPosition;
import io.emberline.fireinfo.domain.EventSelection;
import io.emberline.fireinfo.domain.FireEvent;
import io.emberline.fireinfo.domain.FwiDay;
import io.emberline.fireinfo.domain.FwiHead;
import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.fireinfo.domain.RegimeHead;
import io.emberline.fireinfo.domain.RegimeMonth;
import io.emberline.fireinfo.domain.SpreadCell;
import io.emberline.fireinfo.domain.SpreadHead;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** fireinfo partitions of one release. A layer file the release did not ship has no rows anywhere. */
public interface FireInfoReadPort {

    /** The layer files this release loaded. */
    Set<LayerFile> layers(long releaseId);

    /** Up to {@code limit} events of the selection in sort order, after {@code after} when given. */
    List<FireEvent> eventPage(long releaseId, EventSelection selection, Optional<EventPosition> after, int limit);

    long eventCount(long releaseId, EventSelection selection);

    Optional<FireEvent> event(long releaseId, String eventId);

    FwiHead fwiHead(long releaseId, String aoiId);

    /** The last date of the whole fwi_aoi_day file. */
    Optional<LocalDate> fwiLayerAsOf(long releaseId);

    /** The AOI's days in start..end (inclusive), by date. */
    List<FwiDay> fwiDays(long releaseId, String aoiId, LocalDate start, LocalDate end);

    Optional<SpreadHead> spreadHead(long releaseId);

    /** Cells whose centre lies in the box (edges included). */
    List<SpreadCell> spreadCells(long releaseId, Bbox box);

    RegimeHead regimeHead(long releaseId, String aoiId);

    /** The AOI's months with {@code from ≤ year*100+month ≤ to}, by month. */
    List<RegimeMonth> regimeMonths(long releaseId, String aoiId, int from, int to);
}
