package io.emberline.fireinfo.application.service;

import io.emberline.catalog.api.CatalogApi;
import io.emberline.fireinfo.application.port.in.GetSpreadNowcastUseCase;
import io.emberline.fireinfo.application.port.in.SpreadCellItem;
import io.emberline.fireinfo.application.port.in.SpreadNowcastQuery;
import io.emberline.fireinfo.application.port.in.SpreadNowcastResponse;
import io.emberline.fireinfo.application.port.out.FireInfoReadPort;
import io.emberline.fireinfo.domain.Bbox;
import io.emberline.fireinfo.domain.FireInfoTexts;
import io.emberline.fireinfo.domain.KrArgCoverage;
import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.fireinfo.domain.SpreadCell;
import io.emberline.fireinfo.domain.SpreadHead;
import io.emberline.fireinfo.domain.SpreadLevel;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.json.Coverage;
import io.emberline.shared.release.ReleaseRef;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * Spread nowcast cells (contract {@code getSpreadNowcast}): {@code aoi_id} = cell centre inside the AOI boundary,
 * {@code bbox} = cell centre inside the box; exactly one. Range: the AOIs and boxes of {@link KrArgCoverage}; an AOI
 * the boundary file does not carry cannot be cut, so it answers {@code available=false} too. {@code level} from
 * {@link SpreadLevel}; {@code min_level} keeps that level and above. {@code notice} always.
 */
@Service
class SpreadNowcastService implements GetSpreadNowcastUseCase {

    /** Selected cells: the area's box for the SQL prefilter and the exact test. */
    private record Area(Bbox box, Predicate<SpreadCell> inside) {
    }

    private final FireInfoReadPort port;
    private final CatalogApi catalog;
    private final SpreadAoiShapes shapes;
    private final LayerSupport layers;
    private final EmberlineProperties.Limits limits;

    SpreadNowcastService(FireInfoReadPort port, CatalogApi catalog, SpreadAoiShapes shapes, LayerSupport layers,
                         EmberlineProperties properties) {
        this.port = port;
        this.catalog = catalog;
        this.shapes = shapes;
        this.layers = layers;
        this.limits = properties.limits();
    }

    @Override
    public SpreadNowcastResponse nowcast(ReleaseRef release, SpreadNowcastQuery query) {
        boolean hasAoi = Params.given(query.aoiId());
        boolean hasBbox = Params.given(query.bbox());
        Params.exactlyOneArea(hasAoi, hasBbox);
        SpreadLevel minLevel = query.minLevel() == null ? SpreadLevel.LOW : SpreadLevel.ofCode(query.minLevel()).orElseThrow(() ->
                new InvalidRequestException(ErrorCode.INVALID_PARAM, "min_level", "min_level must be low, moderate or high"));
        Bbox box = hasBbox ? Params.bbox(query.bbox()) : null;
        if (box != null) {
            Params.bboxArea(box, limits.layerBboxAreaDeg2());
        }
        if (hasAoi) {
            catalog.requireAoi(release, query.aoiId(), "aoi_id");
        }
        String normalized = Params.normalized("aoi_id", hasAoi ? query.aoiId() : null, "bbox", hasBbox ? box.canonical() : null,
                "min_level", minLevel.code());
        return layers.cached(release, LayerRoute.SPREAD_NOWCAST, normalized, SpreadNowcastResponse.class, () -> {
            layers.require(release, LayerFile.SPREAD_NOWCAST);
            Optional<Area> area = hasBbox ? Optional.of(new Area(box, c -> true)).filter(a -> KrArgCoverage.covers(box))
                    : aoiArea(release, query.aoiId());
            Optional<SpreadHead> head = port.spreadHead(release.releaseId());
            List<SpreadCellItem> cells = area.map(a -> port.spreadCells(release.releaseId(), a.box()).stream()
                    .filter(a.inside()).filter(c -> c.level().atLeast(minLevel)).map(SpreadCellItem::of).toList()).orElse(List.of());
            if (cells.size() > limits.spreadCellsMax()) {
                throw new InvalidRequestException(ErrorCode.CELLS_TOO_MANY, hasBbox ? "bbox" : "aoi_id", "at most "
                        + limits.spreadCellsMax() + " cells per answer (" + cells.size() + "); use a smaller area or min_level");
            }
            Coverage coverage = area.isPresent() ? Coverage.availableFor(Coverage.Scope.KR_BOX_ARG_SPREAD)
                    : Coverage.unavailable(Coverage.Scope.KR_BOX_ARG_SPREAD, Coverage.Reason.OUT_OF_COVERAGE);
            return new SpreadNowcastResponse(head.map(SpreadHead::asOfDate).map(LocalDate::toString).orElse(null),
                    head.map(SpreadHead::targetDate).map(LocalDate::toString).orElse(null),
                    head.map(SpreadHead::model).orElse(null), head.map(SpreadHead::sourceSatellite).orElse(null),
                    head.map(SpreadHead::weatherAvailable).orElse(false), head.map(SpreadHead::weatherLagDays).orElse(null),
                    hasAoi ? query.aoiId() : null, box == null ? null : box.asList(), cells.size(), cells, coverage,
                    FireInfoTexts.SPREAD_NOTICE, release.dataRelease());
        });
    }

    /** Cells of a covered AOI: centre inside its boundary. Empty outside the range or without a boundary. */
    private Optional<Area> aoiArea(ReleaseRef release, String aoiId) {
        if (!KrArgCoverage.covers(aoiId)) {
            return Optional.empty();
        }
        return shapes.of(release, aoiId).map(s -> new Area(s.bbox(), c -> s.contains(c.cellLat(), c.cellLon())));
    }
}
