package io.emberline.observation.application.service;

import io.emberline.catalog.api.AoiGeometry;
import io.emberline.observation.domain.CellBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pod-local (L1) prepared AOI boundaries per release for "cell center inside the AOI" (oracle: shapely.contains_xy
 * after a bbox prefilter). Keyed by release_id, so a switch needs no invalidation; the last few releases are kept.
 */
@Component
class AoiCellAreas {

    private static final int KEEP = 3;
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Function<JsonNode, Geometry>> BUILDERS = Map.of(
            "Polygon", AoiCellAreas::polygon,
            "MultiPolygon", AoiCellAreas::multiPolygon);

    /** A prepared boundary and its bbox (the prefilter box of the cell query). */
    record Area(PreparedGeometry geometry, CellBox box) {

        boolean containsCenter(float lat, float lon) {
            return geometry.contains(FACTORY.createPoint(new Coordinate(lon, lat)));
        }
    }

    private final Map<Long, Map<String, Optional<Area>>> byRelease = new ConcurrentHashMap<>();

    /** The prepared area of the AOI; empty for AOIs without a boundary (no cell is inside them). */
    Optional<Area> of(long releaseId, AoiGeometry aoi) {
        Map<String, Optional<Area>> areas = byRelease.computeIfAbsent(releaseId, id -> new ConcurrentHashMap<>());
        if (byRelease.size() > KEEP) {
            byRelease.keySet().stream().sorted().limit(byRelease.size() - KEEP).toList().forEach(byRelease::remove);
        }
        return areas.computeIfAbsent(aoi.aoiId(), id -> prepare(aoi));
    }

    boolean isCached(long releaseId, String aoiId) {
        Map<String, Optional<Area>> areas = byRelease.get(releaseId);
        return areas != null && areas.containsKey(aoiId);
    }

    private static Optional<Area> prepare(AoiGeometry aoi) {
        if (!aoi.hasBoundary()) {
            return Optional.empty();
        }
        JsonNode node = JSON.readTree(aoi.geometryJson());
        Function<JsonNode, Geometry> builder = BUILDERS.get(node.path("type").asString(""));
        if (builder == null) {
            return Optional.empty();
        }
        Geometry g = builder.apply(node.get("coordinates"));
        return Optional.of(new Area(PreparedGeometryFactory.prepare(g),
                new CellBox(aoi.west(), aoi.south(), aoi.east(), aoi.north())));
    }

    private static Geometry polygon(JsonNode rings) {
        return polygonOf(rings);
    }

    private static Geometry multiPolygon(JsonNode polygons) {
        List<Polygon> parts = new ArrayList<>();
        polygons.forEach(p -> parts.add(polygonOf(p)));
        return FACTORY.createMultiPolygon(parts.toArray(new Polygon[0]));
    }

    private static Polygon polygonOf(JsonNode rings) {
        List<LinearRing> all = new ArrayList<>();
        for (JsonNode ring : rings) {
            List<Coordinate> coords = new ArrayList<>();
            ring.forEach(xy -> coords.add(new Coordinate(xy.get(0).doubleValue(), xy.get(1).doubleValue())));
            all.add(FACTORY.createLinearRing(coords.toArray(new Coordinate[0])));
        }
        return FACTORY.createPolygon(all.get(0), all.subList(1, all.size()).toArray(new LinearRing[0]));
    }
}
