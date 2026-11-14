package io.emberline.catalog.adapter.out.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads aoi_boundaries.geojson: per feature the aoi_id, the geometry object as-is (numbers kept as written) and
 * its bbox computed with JTS. Only Polygon and MultiPolygon are accepted (geometry builders in a map, no switch).
 */
final class GeoJsonBoundaries {

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final Map<String, Function<JsonNode, Geometry>> BUILDERS = Map.of(
            "Polygon", GeoJsonBoundaries::polygon,
            "MultiPolygon", GeoJsonBoundaries::multiPolygon);

    /** One boundary: geometry JSON text (null when the feature has no geometry) and its envelope. */
    record Boundary(String aoiId, String type, String geometryJson, Envelope bbox) {
    }

    /** Parse result: boundaries by aoi_id plus problems found (empty = valid). */
    record Parsed(Map<String, Boundary> boundaries, List<String> problems) {
    }

    private GeoJsonBoundaries() {
    }

    static Parsed read(Path file) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(file.toFile());
        } catch (RuntimeException e) {
            return new Parsed(Map.of(), List.of("not JSON"));
        }
        Map<String, Boundary> out = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        if (!"FeatureCollection".equals(text(root.get("type"))) || root.get("features") == null || !root.get("features").isArray()) {
            return new Parsed(Map.of(), List.of("not a FeatureCollection"));
        }
        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            String aoiId = props == null ? null : text(props.get("aoi_id"));
            if (aoiId == null) {
                problems.add("feature without properties.aoi_id");
                continue;
            }
            JsonNode geometry = feature.get("geometry");
            if (geometry == null || geometry.isNull()) {
                out.put(aoiId, new Boundary(aoiId, null, null, null));
                continue;
            }
            String type = text(geometry.get("type"));
            Function<JsonNode, Geometry> builder = type == null ? null : BUILDERS.get(type);
            if (builder == null) {
                problems.add("geometry type of " + aoiId);
                continue;
            }
            try {
                Geometry g = builder.apply(geometry.get("coordinates"));
                out.put(aoiId, new Boundary(aoiId, type, JSON.writeValueAsString(geometry), g.getEnvelopeInternal()));
            } catch (RuntimeException e) {
                problems.add("geometry of " + aoiId);
            }
        }
        return new Parsed(out, problems);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
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
            throw new IllegalArgumentException("empty polygon");
        }
        return FACTORY.createPolygon(all.get(0), all.subList(1, all.size()).toArray(new LinearRing[0]));
    }
}
