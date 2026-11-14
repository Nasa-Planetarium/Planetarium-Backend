package io.emberline.hotspots;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test-side reading of aoi_boundaries.geojson into plain JTS geometries (expected values, independent of main code). */
final class Boundaries {

    private static final GeometryFactory GF = new GeometryFactory();

    private Boundaries() {
    }

    static Geometry of(Path geojson, String aoiId) {
        JsonNode root = JsonMapper.builder().build().readTree(geojson.toFile());
        for (JsonNode f : root.get("features")) {
            if (aoiId.equals(f.get("properties").get("aoi_id").asString())) {
                JsonNode g = f.get("geometry");
                List<Polygon> polygons = new ArrayList<>();
                if ("Polygon".equals(g.get("type").asString())) {
                    polygons.add(polygon(g.get("coordinates")));
                } else {
                    g.get("coordinates").forEach(p -> polygons.add(polygon(p)));
                }
                return GF.createMultiPolygon(polygons.toArray(new Polygon[0]));
            }
        }
        throw new IllegalArgumentException(aoiId);
    }

    private static Polygon polygon(JsonNode rings) {
        List<LinearRing> out = new ArrayList<>();
        for (JsonNode ring : rings) {
            List<Coordinate> cs = new ArrayList<>();
            ring.forEach(xy -> cs.add(new Coordinate(xy.get(0).asDouble(), xy.get(1).asDouble())));
            out.add(GF.createLinearRing(cs.toArray(new Coordinate[0])));
        }
        return GF.createPolygon(out.get(0), out.subList(1, out.size()).toArray(new LinearRing[0]));
    }
}
