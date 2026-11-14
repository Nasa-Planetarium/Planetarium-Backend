package io.emberline.observation.application.service;

import io.emberline.catalog.api.AoiGeometry;
import io.emberline.catalog.api.AoiGeometryApi;
import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.observation.application.port.in.CellCollection;
import io.emberline.observation.application.port.in.CellsQuery;
import io.emberline.observation.application.port.in.ListCellsUseCase;
import io.emberline.observation.application.port.out.CellReadPort;
import io.emberline.observation.domain.CellBox;
import io.emberline.observation.domain.CellGrid;
import io.emberline.observation.domain.CellRow;
import io.emberline.shared.Units;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.release.ReleaseRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * GET /api/v1/cells: cells of one month for an AOI (bbox prefilter from the catalog boundary, then cell center inside
 * the boundary with a JTS PreparedGeometry — the oracle rule) or for a bbox (cell center inside the box). Cost is
 * checked before reading: level world/continent, an AOI with more than {@code cells-max} cells or a bbox covering
 * more than {@code cells-max} cells → 400 range_too_large (40103).
 */
@Service
class CellQueryService implements ListCellsUseCase {

    static final Set<String> TOO_LARGE_LEVELS = Set.of("world", "continent");

    private final CellReadPort cells;
    private final CatalogApi catalog;
    private final AoiGeometryApi geometries;
    private final AoiCellAreas areas;
    private final CellCaseRegistry cases;
    private final int cellsMax;
    private final int prefilterRows;

    CellQueryService(CellReadPort cells, CatalogApi catalog, AoiGeometryApi geometries, AoiCellAreas areas,
                     CellCaseRegistry cases, EmberlineProperties properties, ObservationProperties observation) {
        this.cells = cells;
        this.catalog = catalog;
        this.geometries = geometries;
        this.areas = areas;
        this.cases = cases;
        this.cellsMax = properties.limits().cellsMax();
        this.prefilterRows = observation.aoiPrefilterRows();
    }

    @Override
    public CellCollection cells(ReleaseRef release, CellsQuery query) {
        boolean byAoi = query.aoiId() != null && !query.aoiId().isEmpty();
        boolean byBbox = query.bbox() != null && !query.bbox().isEmpty();
        if (byAoi == byBbox) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, byAoi ? "bbox" : "aoi_id", "give exactly one of aoi_id or bbox");
        }
        int year = Integer.parseInt(query.month().substring(0, 4));
        int month = Integer.parseInt(query.month().substring(5, 7));
        List<CellRow> rows = byAoi ? aoiRows(release, query.aoiId(), year, month) : bboxRows(release, parseBbox(query.bbox()), year, month);
        List<CellCollection.Feature> features = new ArrayList<>(rows.size());
        for (CellRow r : rows) {
            features.add(new CellCollection.Feature("Feature", r.cellId(),
                    new CellCollection.Polygon("Polygon", List.<double[][]>of(CellGrid.ring(r.cellId()))), cases.properties(r)));
        }
        String status = cells.monthStatus(release.releaseId(), year, month).orElse("out_of_range");
        CellBox box = byBbox ? parseBbox(query.bbox()) : null;
        return new CellCollection("FeatureCollection", new CellCollection.Properties(byAoi ? query.aoiId() : null,
                box == null ? null : List.of(box.west(), box.south(), box.east(), box.north()), year, month, Units.UNIT,
                CellGrid.SIZE_DEG, features.size(), status, CellCollection.ZERO_RULE, release.methodVersion()), features);
    }

    private List<CellRow> aoiRows(ReleaseRef release, String aoiId, int year, int month) {
        AoiSummary aoi = catalog.requireAoi(release, aoiId, "aoi_id");
        if (TOO_LARGE_LEVELS.contains(aoi.level()) || aoi.nCells() > cellsMax) {
            throw tooMany("aoi_id", "aoi " + aoi.aoiId() + " has more than " + cellsMax + " cells; choose a smaller area");
        }
        AoiGeometry geometry = geometries.requireGeometry(release, aoiId, "aoi_id");
        Optional<AoiCellAreas.Area> area = areas.of(release.releaseId(), geometry);
        if (area.isEmpty()) {
            return List.of();
        }
        List<CellRow> candidates = cells.cellsInBox(release.releaseId(), year, month, area.get().box(), prefilterRows + 1);
        if (candidates.size() > prefilterRows) {
            throw tooMany("aoi_id", "aoi " + aoi.aoiId() + " is too large for cells; choose a smaller area");
        }
        List<CellRow> inside = candidates.stream().filter(r -> area.get().containsCenter(r.cellLat(), r.cellLon())).toList();
        if (inside.size() > cellsMax) {
            throw tooMany("aoi_id", "more than " + cellsMax + " cells; choose a smaller area");
        }
        return inside;
    }

    private List<CellRow> bboxRows(ReleaseRef release, CellBox box, int year, int month) {
        if (box.cellsCovered() > cellsMax) {
            throw tooMany("bbox", "bbox covers more than " + cellsMax + " cells; choose a smaller area");
        }
        return cells.cellsInBox(release.releaseId(), year, month, box, cellsMax + 1);
    }

    /** {@code w,s,e,n} in degrees: four finite numbers, -180 ≤ w &lt; e ≤ 180, -90 ≤ s &lt; n ≤ 90 (contract Bbox). */
    static CellBox parseBbox(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length != 4) {
            throw badBbox();
        }
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                v[i] = Double.parseDouble(parts[i].strip());
            } catch (NumberFormatException e) {
                throw badBbox();
            }
            if (!Double.isFinite(v[i])) {
                throw badBbox();
            }
        }
        if (!(-180 <= v[0] && v[0] < v[2] && v[2] <= 180 && -90 <= v[1] && v[1] < v[3] && v[3] <= 90)) {
            throw badBbox();
        }
        return new CellBox(v[0], v[1], v[2], v[3]);
    }

    private static InvalidRequestException badBbox() {
        return new InvalidRequestException(ErrorCode.INVALID_PARAM, "bbox",
                "bbox must be west,south,east,north in degrees with west < east and south < north");
    }

    private static InvalidRequestException tooMany(String param, String detail) {
        return new InvalidRequestException(ErrorCode.CELLS_TOO_MANY, param, detail);
    }
}
