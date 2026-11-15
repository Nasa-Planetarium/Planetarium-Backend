package io.emberline.harmonized.adapter.out.geo;

import io.emberline.harmonized.adapter.out.HarmonizedMetrics;
import io.emberline.harmonized.adapter.out.HarmonizedSettings;
import io.emberline.harmonized.adapter.out.parquet.ReleaseFiles;
import io.emberline.harmonized.api.AoiCellRule;
import io.emberline.harmonized.application.port.out.AoiCellsPort;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.sql.SqlResource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AOI cell set (ruling R-SP1): cells whose centre lies inside the AOI boundary. The boundary is the AOI's feature in
 * the release's aoi_boundaries.geojson (the file catalog loaded; its bbox is the catalog bbox), the candidates are the
 * {@code harmonized.cell_center} rows inside that bbox, the test is JTS PreparedGeometry contains. Cached per
 * (release, aoi_id) in a bounded LRU. The same boundary and test answer {@link AoiCellRule} for other contexts' cell
 * centres (outlook's 0.5° grid), so the rule has one implementation.
 */
@Component
class AoiCellsAdapter implements AoiCellsPort, AoiCellRule {

    static final String ROUTE = "summary";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final Map<String, Function<JsonNode, Geometry>> BUILDERS = Map.of(
            "Polygon", AoiCellsAdapter::polygon,
            "MultiPolygon", AoiCellsAdapter::multiPolygon);

    private final JdbcClient jdbc;
    private final ReleaseFiles files;
    private final HarmonizedMetrics metrics;
    private final Map<String, Optional<long[]>> cache;

    AoiCellsAdapter(JdbcClient jdbc, ReleaseFiles files, HarmonizedMetrics metrics, HarmonizedSettings settings) {
        this.jdbc = jdbc;
        this.files = files;
        this.metrics = metrics;
        int max = settings.cellSetEntries();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Optional<long[]>> eldest) {
                return size() > max;
            }
        });
    }

    @Override
    public Optional<long[]> cellsInside(ReleaseRef release, String aoiId) {
        String key = release.releaseId() + "\u0000" + aoiId;
        Optional<long[]> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Optional<long[]> computed = boundary(release, aoiId).map(g -> inside(release, g));
        cache.put(key, computed);
        return computed;
    }

    @Override
    public Optional<boolean[]> centresInside(ReleaseRef release, String aoiId, double[] lats, double[] lons) {
        if (lats.length != lons.length) {
            throw new IllegalArgumentException("lats and lons differ in length");
        }
        return boundary(release, aoiId).map(boundary -> {
            Envelope bbox = boundary.getEnvelopeInternal();
            PreparedGeometry prepared = PreparedGeometryFactory.prepare(boundary);
            boolean[] out = new boolean[lats.length];
            for (int i = 0; i < lats.length; i++) {
                out[i] = bbox.contains(lons[i], lats[i]) && centreInside(prepared, lats[i], lons[i]);
            }
            return out;
        });
    }

    /** R-SP1: the cell centre lies inside the boundary (JTS contains — on the boundary line is outside). */
    private static boolean centreInside(PreparedGeometry boundary, double lat, double lon) {
        return boundary.contains(FACTORY.createPoint(new Coordinate(lon, lat)));
    }

    private Optional<Geometry> boundary(ReleaseRef release, String aoiId) {
        Path file = files.require(release, ReleaseFiles.BOUNDARIES);
        JsonNode root;
        try {
            root = JSON.readTree(file.toFile());
        } catch (RuntimeException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "area boundaries are not available", e);
        }
        JsonNode features = root.get("features");
        if (features == null || !features.isArray()) {
            return Optional.empty();
        }
        for (JsonNode f : features) {
            JsonNode props = f.get("properties");
            JsonNode id = props == null ? null : props.get("aoi_id");
            if (id != null && aoiId.equals(id.asString())) {
                JsonNode geometry = f.get("geometry");
                JsonNode type = geometry == null || geometry.isNull() ? null : geometry.get("type");
                Function<JsonNode, Geometry> builder = type == null ? null : BUILDERS.get(type.asString());
                return builder == null ? Optional.empty() : Optional.of(builder.apply(geometry.get("coordinates")));
            }
        }
        return Optional.empty();
    }

    private long[] inside(ReleaseRef release, Geometry boundary) {
        Envelope bbox = boundary.getEnvelopeInternal();
        PreparedGeometry prepared = PreparedGeometryFactory.prepare(boundary);
        metrics.sqlStatement(ROUTE);
        List<double[]> candidates;
        try {
            candidates = jdbc.sql(SqlResource.text("harmonized/select_cell_centers.sql"))
                    .param("releaseId", release.releaseId())
                    .param("south", (float) bbox.getMinY() - 1e-4f).param("north", (float) bbox.getMaxY() + 1e-4f)
                    .param("west", (float) bbox.getMinX() - 1e-4f).param("east", (float) bbox.getMaxX() + 1e-4f)
                    .query((rs, n) -> new double[] {rs.getLong(1), rs.getFloat(2), rs.getFloat(3)}).list();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "cell centres are not available", e);
        }
        List<Long> ids = new ArrayList<>();
        for (double[] c : candidates) {
            if (centreInside(prepared, c[1], c[2])) {
                ids.add((long) c[0]);
            }
        }
        long[] out = ids.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(out);
        return out;
    }

    private static Geometry polygon(JsonNode rings) {
        return polygonOf(rings);
    }

    private static Geometry multiPolygon(JsonNode polygons) {
        List<Polygon> parts = new ArrayList<>();
        for (JsonNode p : polygons) {
            parts.add(polygonOf(p));
        }
        return FACTORY.createMultiPolygon(parts.toArray(new Polygon[0]));
    }

    private static Polygon polygonOf(JsonNode rings) {
        List<LinearRing> all = new ArrayList<>();
        for (JsonNode ring : rings) {
            List<Coordinate> coords = new ArrayList<>();
            for (JsonNode xy : ring) {
                coords.add(new Coordinate(xy.get(0).doubleValue(), xy.get(1).doubleValue()));
            }
            all.add(FACTORY.createLinearRing(coords.toArray(new Coordinate[0])));
        }
        return FACTORY.createPolygon(all.get(0), all.subList(1, all.size()).toArray(new LinearRing[0]));
    }
}
