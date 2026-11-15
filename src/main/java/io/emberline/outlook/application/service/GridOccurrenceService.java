package io.emberline.outlook.application.service;

import io.emberline.outlook.application.port.in.GetGridOccurrenceUseCase;
import io.emberline.outlook.application.port.in.GridCellFull;
import io.emberline.outlook.application.port.in.GridCellMap;
import io.emberline.outlook.application.port.in.GridCellView;
import io.emberline.outlook.application.port.in.GridOccurrenceQuery;
import io.emberline.outlook.application.port.in.GridOccurrenceResponse;
import io.emberline.outlook.application.port.in.LayerBody;
import io.emberline.outlook.application.port.in.OccurrenceCsv;
import io.emberline.outlook.application.port.out.LayerBodyCachePort;
import io.emberline.outlook.domain.Bbox;
import io.emberline.outlook.domain.DisplayRound;
import io.emberline.outlook.domain.GridCell;
import io.emberline.outlook.domain.GridLayer;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.json.Coverage;
import io.emberline.shared.release.ReleaseRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The 0.5° grid as the file has it (A1: nothing recomputed). {@code bbox} keeps the cells whose centre lies in the box
 * (absent = the whole grid, 52,855 cells), {@code min_p_any} drops cells below it, {@code fields} picks the cell shape
 * and display rounding. A box with no cell at all answers {@code coverage.available=false, reason=no_cells} (cells
 * absent from the file are "not a target", MAPPING §8.3); cells present but all below {@code min_p_any} answer an
 * empty list with {@code available=true}. The box area is capped by {@code Version.limits} layer_bbox_area_deg2.
 * The two whole-grid bodies (map, full) are serialized once per release and kept in memory and redis-cache.
 */
@Service
class GridOccurrenceService implements GetGridOccurrenceUseCase {

    static final String FULL = "full";
    static final String MAP = "map";
    static final double CELL_SIZE_DEG = 0.5;
    /** Contract getGridOccurrence text/csv {@code x-csv-columns}. */
    static final List<String> CSV_COLUMNS = List.of("cell_id", "lat", "lon", "target_month", "p_any", "p_none",
            "baseline_p", "vs_normal", "persistent_source", "adm0", "continent", "model", "as_of_date");

    private final OccurrenceLayers layers;
    private final LayerBodyCachePort bodies;
    private final double maxAreaDeg2;

    GridOccurrenceService(OccurrenceLayers layers, LayerBodyCachePort bodies, EmberlineProperties properties) {
        this.layers = layers;
        this.bodies = bodies;
        this.maxAreaDeg2 = properties.limits().layerBboxAreaDeg2();
    }

    @Override
    public LayerBody grid(ReleaseRef release, GridOccurrenceQuery query) {
        boolean map = MAP.equals(query.fields());
        double min = minPAny(query.minPAny());
        Optional<Bbox> box = query.bbox() == null || query.bbox().isBlank() ? Optional.empty() : Optional.of(bbox(query.bbox()));
        GridLayer layer = layers.grid(release);
        List<GridCell> inBox = box.map(b -> layer.cells().stream().filter(c -> b.contains(c.lat(), c.lon())).toList())
                .orElse(layer.cells());
        List<GridCell> cells = min > 0 ? inBox.stream().filter(c -> c.pAny() >= min).toList() : inBox;
        Coverage coverage = inBox.isEmpty()
                ? Coverage.unavailable(Coverage.Scope.WORLD_LAND_DETECTED, Coverage.Reason.NO_CELLS)
                : Coverage.availableFor(Coverage.Scope.WORLD_LAND_DETECTED);
        List<Double> boxOut = box.map(b -> List.of(b.west(), b.south(), b.east(), b.north())).orElse(null);
        String normalized = "bbox=" + (boxOut == null ? "" : join(boxOut)) + "&min_p_any=" + min + "&fields=" + (map ? MAP : FULL);
        boolean wholeGrid = box.isEmpty() && min == 0;
        return new LayerBody(cells.size(), () -> bodies.json(release, LayerRoute.OCCURRENCE_GRID, normalized, wholeGrid,
                () -> response(release, layer, boxOut, cells, map, coverage)),
                () -> csv(layer, boxOut, cells));
    }

    private static GridOccurrenceResponse response(ReleaseRef release, GridLayer layer, List<Double> bbox,
                                                   List<GridCell> cells, boolean map, Coverage coverage) {
        List<GridCellView> views = new ArrayList<>(cells.size());
        for (GridCell c : cells) {
            views.add(map ? mapView(c) : fullView(c));
        }
        return new GridOccurrenceResponse(layer.targetMonth(), layer.asOfDate(), layer.model(), CELL_SIZE_DEG, bbox,
                views.size(), views, coverage, OccurrenceTexts.GRID_NOTICE, release.dataRelease());
    }

    private static GridCellView fullView(GridCell c) {
        int d = DisplayRound.FULL;
        return new GridCellFull(c.cellId(), DisplayRound.round(c.lat(), d), DisplayRound.round(c.lon(), d),
                DisplayRound.round(c.pAny(), d), DisplayRound.round(c.pNone(), d), DisplayRound.round(c.baselineP(), d),
                c.vsNormal().code(), c.persistentSource(), c.adm0(), c.continent(), DisplayRound.round(c.rate5y(), d),
                c.lag1Projected());
    }

    private static GridCellView mapView(GridCell c) {
        int d = DisplayRound.MAP;
        return new GridCellMap(c.cellId(), DisplayRound.round(c.lat(), d), DisplayRound.round(c.lon(), d),
                DisplayRound.round(c.pAny(), d), DisplayRound.round(c.baselineP(), d), c.vsNormal().code(),
                c.persistentSource());
    }

    /** CSV columns as {@code x-csv-columns}, values as stored (unrounded). */
    private static OccurrenceCsv csv(GridLayer layer, List<Double> bbox, List<GridCell> cells) {
        List<List<Object>> rows = new ArrayList<>(cells.size());
        for (GridCell c : cells) {
            rows.add(Arrays.asList(c.cellId(), c.lat(), c.lon(), layer.targetMonth(), c.pAny(), c.pNone(),
                    c.baselineP(), c.vsNormal().code(), c.persistentSource(), c.adm0(), c.continent(), layer.model(),
                    layer.asOfDate()));
        }
        String area = bbox == null ? "world" : join(bbox).replace(',', '_');
        return new OccurrenceCsv("emberline_occurrence_grid_" + layer.targetMonth() + "_" + area + ".csv", CSV_COLUMNS, rows);
    }

    private static double minPAny(Double value) {
        if (value == null) {
            return 0;
        }
        if (!(value >= 0 && value <= 1)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "min_p_any", "min_p_any must be between 0 and 1");
        }
        return value;
    }

    /**
     * west,south,east,north within ±180 / ±90, south &lt; north, west ≠ east; west &gt; east crosses the antimeridian
     * (contract BboxOptional). Area over layer_bbox_area_deg2 → 400 range_too_large 40105.
     */
    Bbox bbox(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            throw badBbox();
        }
        double[] v = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                v[i] = Double.parseDouble(parts[i].strip());
                if (!Double.isFinite(v[i])) {
                    throw badBbox();
                }
            }
        } catch (NumberFormatException e) {
            throw badBbox();
        }
        if (v[0] < -180 || v[0] > 180 || v[2] < -180 || v[2] > 180 || v[1] < -90 || v[3] > 90 || v[1] >= v[3] || v[0] == v[2]) {
            throw badBbox();
        }
        Bbox box = new Bbox(v[0], v[1], v[2], v[3]);
        if (box.areaDeg2() > maxAreaDeg2) {
            throw new InvalidRequestException(ErrorCode.BBOX_AREA_TOO_LARGE, "bbox", "bbox area is limited to "
                    + plain(maxAreaDeg2) + " deg² (Version.limits layer_bbox_area_deg2); this box is "
                    + plain(Math.round(box.areaDeg2() * 100) / 100.0) + " deg². Leave bbox out for the whole grid.");
        }
        return box;
    }

    private static InvalidRequestException badBbox() {
        return new InvalidRequestException(ErrorCode.INVALID_PARAM, "bbox",
                "bbox must be west,south,east,north with south < north, west ≠ east (west > east crosses the antimeridian), "
                        + "longitudes within ±180 and latitudes within ±90");
    }

    private static String join(List<Double> values) {
        return String.join(",", values.stream().map(String::valueOf).toList());
    }

    private static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
