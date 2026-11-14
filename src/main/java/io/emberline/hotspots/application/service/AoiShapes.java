package io.emberline.hotspots.application.service;

import io.emberline.catalog.api.CatalogApi;
import io.emberline.hotspots.domain.BboxArea;
import io.emberline.hotspots.domain.PointFilter;
import io.emberline.shared.release.ReleaseRef;
import java.util.ArrayList;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AOI boundaries as prepared JTS geometries (point-in-polygon after the bbox prefilter), kept for the last
 * {@value #KEEP} (release, aoi_id) pairs. GeoJSON Polygon and MultiPolygon only (builders in a map, no switch).
 */
@Component
class AoiShapes {

    static final int KEEP = 64;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final Map<String, Function<JsonNode, Geometry>> BUILDERS = Map.of(
            "Polygon", AoiShapes::polygon,
            "MultiPolygon", AoiShapes::multiPolygon);

    /** A boundary's bbox and the "point lies inside" test (JTS contains: the boundary line itself is outside). */
    record Shape(BboxArea bbox, PointFilter inside) {
    }

    private record Key(long releaseId, String aoiId) {
    }

    private final CatalogApi catalog;
    private final Map<Key, Optional<Shape>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Optional<Shape>> eldest) {
            return size() > KEEP;
        }
    };

    AoiShapes(CatalogApi catalog) {
        this.catalog = catalog;
    }

    /** The boundary of an existing AOI; empty when the release has no (usable) boundary for it. */
    Optional<Shape> of(ReleaseRef release, String aoiId) {
        Key key = new Key(release.releaseId(), aoiId);
        synchronized (cache) {
            Optional<Shape> hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        Optional<Shape> shape = catalog.boundaryGeometry(release, aoiId).flatMap(AoiShapes::parse);
        synchronized (cache) {
            cache.put(key, shape);
        }
        return shape;
    }

    static Optional<Shape> parse(String geometryJson) {
        JsonNode node = JSON.readTree(geometryJson);
        JsonNode type = node.get("type");
        Function<JsonNode, Geometry> builder = type == null ? null : BUILDERS.get(type.asString());
        if (builder == null || node.get("coordinates") == null) {
            return Optional.empty();
        }
        Geometry g = builder.apply(node.get("coordinates"));
        if (g.isEmpty()) {
            return Optional.empty();
        }
        Envelope e = g.getEnvelopeInternal();
        PreparedGeometry prepared = PreparedGeometryFactory.prepare(g);
        return Optional.of(new Shape(new BboxArea(e.getMinX(), e.getMinY(), e.getMaxX(), e.getMaxY()),
                (lat, lon) -> prepared.contains(FACTORY.createPoint(new Coordinate(lon, lat)))));
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
        if (all.isEmpty()) {
            return FACTORY.createPolygon();
        }
        return FACTORY.createPolygon(all.get(0), all.subList(1, all.size()).toArray(new LinearRing[0]));
    }
}
