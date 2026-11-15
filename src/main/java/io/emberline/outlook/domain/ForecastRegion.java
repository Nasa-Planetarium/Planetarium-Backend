package io.emberline.outlook.domain;

import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.validation.Patterns;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/**
 * The {@code region} of {@code POST /forecast} (model contract: aoi_id | [서, 남, 동, 북] | GeoJSON Polygon/MultiPolygon),
 * validated and written back in one canonical JSON form ({@link #json()}): the form the forecast service receives and
 * the cache key is built from. Numbers are written as the shortest decimal of the double the service will parse
 * (Python {@code json} reads them as doubles too), {@code -0.0} as {@code 0.0}; polygon positions keep their order.
 */
public sealed interface ForecastRegion {

    String PARAM = "region";

    /** Canonical JSON of the region value. */
    String json();

    /** An aoi_id of aoi_list; only its format is checked here, existence is the service's answer. */
    record Aoi(String aoiId) implements ForecastRegion {

        private static final Pattern FORMAT = Pattern.compile(Patterns.AOI_ID);

        public Aoi {
            if (aoiId == null || !FORMAT.matcher(aoiId).matches()) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, PARAM,
                        "region as a string must be an aoi_id (letters, digits and _ : . + ? -, 1 to 64 characters)");
            }
        }

        @Override
        public String json() {
            return "\"" + aoiId + "\"";
        }
    }

    /**
     * [west, south, east, north] in degrees. west &lt; east: the model builds {@code box(west, south, east, north)},
     * which would read an antimeridian box (west &gt; east) as the strip between the two edges instead.
     */
    record Box(double west, double south, double east, double north) implements ForecastRegion {

        public Box {
            if (!finite(west, south, east, north)) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, PARAM, "region bbox values must be finite numbers");
            }
            if (west < -180 || east > 180 || south < -90 || north > 90) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, PARAM,
                        "region bbox must lie within longitude -180..180 and latitude -90..90");
            }
            if (!(west < east) || !(south < north)) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, PARAM,
                        "region bbox must be [west, south, east, north] with west < east and south < north");
            }
        }

        @Override
        public String json() {
            return "[" + number(west) + "," + number(south) + "," + number(east) + "," + number(north) + "]";
        }
    }

    /**
     * GeoJSON Polygon or MultiPolygon: {@code polygons} → rings → positions [lon, lat]. A Polygon has exactly one entry.
     * Every ring has at least 4 positions and ends where it starts; positions are 2 finite numbers inside ±180 / ±90;
     * the positions of all rings together are at most {@code maxVertices}; each polygon is valid for JTS (no
     * self-intersection, no overlapping members — shapely's intersection in the model would fail on it).
     */
    record Shape(String type, List<List<List<double[]>>> polygons) implements ForecastRegion {

        public static final String POLYGON = "Polygon";
        public static final String MULTI_POLYGON = "MultiPolygon";
        private static final GeometryFactory GEOMETRY = new GeometryFactory();

        public Shape {
            polygons = polygons.stream().map(p -> p.stream().map(List::copyOf).toList()).toList();
        }

        /** Checks the vertex cap first (cheap), then rings, positions and validity. */
        public static Shape of(String type, List<List<List<double[]>>> polygons, int maxVertices) {
            if (!POLYGON.equals(type) && !MULTI_POLYGON.equals(type)) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, PARAM,
                        "region as an object must be a GeoJSON Polygon or MultiPolygon");
            }
            long vertices = polygons.stream().flatMap(List::stream).mapToLong(List::size).sum();
            if (vertices > maxVertices) {
                throw new InvalidRequestException(ErrorCode.FORECAST_POLYGON_TOO_COMPLEX, PARAM,
                        "region polygon has " + vertices + " positions; at most " + maxVertices + " are allowed");
            }
            if (polygons.isEmpty() || (POLYGON.equals(type) && polygons.size() != 1)) {
                throw geometry("region " + type + " has no polygon");
            }
            Polygon[] built = polygons.stream().map(Shape::polygon).toArray(Polygon[]::new);
            Geometry whole = built.length == 1 ? built[0] : GEOMETRY.createMultiPolygon(built);
            if (!whole.isValid()) {
                throw geometry("region " + type + " is not valid (a self-intersecting ring, a hole outside its shell, "
                        + "or overlapping polygons)");
            }
            return new Shape(type, polygons);
        }

        private static Polygon polygon(List<List<double[]>> rings) {
            if (rings.isEmpty()) {
                throw geometry("region polygon needs an outer ring");
            }
            List<LinearRing> built = new ArrayList<>();
            for (List<double[]> ring : rings) {
                if (ring.size() < 4) {
                    throw geometry("region polygon rings need at least 4 positions");
                }
                Coordinate[] coordinates = new Coordinate[ring.size()];
                for (int i = 0; i < ring.size(); i++) {
                    double[] p = ring.get(i);
                    if (p.length != 2 || !finite(p[0], p[1])) {
                        throw geometry("region polygon positions must be [longitude, latitude] with finite numbers");
                    }
                    if (p[0] < -180 || p[0] > 180 || p[1] < -90 || p[1] > 90) {
                        throw geometry("region polygon must lie within longitude -180..180 and latitude -90..90");
                    }
                    coordinates[i] = new Coordinate(p[0], p[1]);
                }
                double[] first = ring.get(0);
                double[] last = ring.get(ring.size() - 1);
                if (first[0] != last[0] || first[1] != last[1]) {
                    throw geometry("region polygon rings must be closed (the last position equal to the first)");
                }
                built.add(GEOMETRY.createLinearRing(coordinates));
            }
            return GEOMETRY.createPolygon(built.get(0), built.subList(1, built.size()).toArray(LinearRing[]::new));
        }

        private static InvalidRequestException geometry(String detail) {
            return new InvalidRequestException(ErrorCode.INVALID_GEOMETRY, PARAM, detail);
        }

        @Override
        public String json() {
            StringBuilder b = new StringBuilder("{\"coordinates\":");
            if (MULTI_POLYGON.equals(type)) {
                b.append('[');
                for (int i = 0; i < polygons.size(); i++) {
                    b.append(i == 0 ? "" : ",");
                    rings(b, polygons.get(i));
                }
                b.append(']');
            } else {
                rings(b, polygons.get(0));
            }
            return b.append(",\"type\":\"").append(type).append("\"}").toString();
        }

        private static void rings(StringBuilder b, List<List<double[]>> rings) {
            b.append('[');
            for (int r = 0; r < rings.size(); r++) {
                b.append(r == 0 ? "[" : ",[");
                List<double[]> ring = rings.get(r);
                for (int i = 0; i < ring.size(); i++) {
                    b.append(i == 0 ? "[" : ",[").append(number(ring.get(i)[0])).append(',').append(number(ring.get(i)[1])).append(']');
                }
                b.append(']');
            }
            b.append(']');
        }
    }

    private static boolean finite(double... values) {
        for (double v : values) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /** Shortest decimal that reads back as the same double; {@code -0.0} as {@code 0.0}. */
    static String number(double v) {
        return Double.toString(v == 0 ? 0.0 : v);
    }
}
