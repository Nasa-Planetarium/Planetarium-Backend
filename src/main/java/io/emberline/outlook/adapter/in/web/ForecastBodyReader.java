package io.emberline.outlook.adapter.in.web;

import io.emberline.outlook.application.port.in.ForecastQuery;
import io.emberline.outlook.domain.ForecastPeriod;
import io.emberline.outlook.domain.ForecastRegion;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.validation.Patterns;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the {@code POST /forecast} body (contract/MAPPING.md §9.2) into a validated {@link ForecastQuery}: one JSON
 * object (duplicate member names refused), members {@code region}, {@code horizon_days}, {@code start}, {@code end}
 * only (JSON {@code null} = absent), and exactly one period form — {@code horizon_days} (JSON integer, optionally with
 * {@code start}) or {@code start} + {@code end}. The limits are {@code emberline.limits.forecast-*}. The body size was
 * capped before ({@link ForecastRequestFilter}).
 */
@Component
class ForecastBodyReader {

    static final Set<String> MEMBERS = Set.of("region", "horizon_days", "start", "end");
    private static final Set<String> GEOMETRY_MEMBERS = Set.of("type", "coordinates");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final EmberlineProperties.Limits limits;

    ForecastBodyReader(EmberlineProperties properties) {
        this.limits = properties.limits();
    }

    ForecastQuery read(byte[] body) {
        JsonNode root;
        try {
            root = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (JacksonException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, null,
                    "request body must be one JSON object (no duplicate member names)");
        }
        for (String name : root.propertyNames()) {
            if (!MEMBERS.contains(name)) {
                throw new InvalidRequestException(ErrorCode.INVALID_PARAM, name.matches("^[A-Za-z0-9_]{1,64}$") ? name : null,
                        "unknown member; the body takes region, horizon_days, start and end");
            }
        }
        return new ForecastQuery(region(present(root, "region")), period(root));
    }

    private ForecastPeriod period(JsonNode root) {
        JsonNode horizon = present(root, "horizon_days");
        JsonNode start = present(root, "start");
        JsonNode end = present(root, "end");
        if (horizon != null) {
            if (end != null) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_PERIOD, "end",
                        "end cannot be combined with horizon_days; send horizon_days (optionally with start) or start and end");
            }
            if (!horizon.isIntegralNumber() || !horizon.canConvertToLong()) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_PERIOD, "horizon_days",
                        "horizon_days must be an integer from 1 to " + limits.forecastHorizonDaysMax());
            }
            return ForecastPeriod.Horizon.of(horizon.longValue(), start == null ? null : date(start, "start"),
                    limits.forecastHorizonDaysMax());
        }
        if (start == null || end == null) {
            throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_PERIOD, start == null ? "start" : "end",
                    "send horizon_days (optionally with start), or start and end");
        }
        return ForecastPeriod.Range.of(date(start, "start"), date(end, "end"), limits.forecastSpanDays());
    }

    private static LocalDate date(JsonNode node, String param) {
        if (node.isString() && node.asString().matches(Patterns.DATE)) {
            try {
                return LocalDate.parse(node.asString());
            } catch (DateTimeParseException e) {
                // falls through to the error
            }
        }
        throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_PERIOD, param, param + " must be a date YYYY-MM-DD");
    }

    private ForecastRegion region(JsonNode node) {
        if (node == null) {
            throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, ForecastRegion.PARAM,
                    "region is required: an aoi_id, [west, south, east, north] or a GeoJSON Polygon/MultiPolygon");
        }
        if (node.isString()) {
            return new ForecastRegion.Aoi(node.asString());
        }
        if (node.isArray()) {
            if (node.size() != 4) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, ForecastRegion.PARAM,
                        "region as an array must be [west, south, east, north]");
            }
            double[] v = new double[4];
            for (int i = 0; i < 4; i++) {
                v[i] = number(node.get(i));
            }
            return new ForecastRegion.Box(v[0], v[1], v[2], v[3]);
        }
        if (node.isObject()) {
            return shape(node);
        }
        throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, ForecastRegion.PARAM,
                "region must be an aoi_id, [west, south, east, north] or a GeoJSON Polygon/MultiPolygon");
    }

    private ForecastRegion shape(JsonNode node) {
        for (String name : node.propertyNames()) {
            if (!GEOMETRY_MEMBERS.contains(name)) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, ForecastRegion.PARAM,
                        "region as an object takes type and coordinates only (a GeoJSON geometry, not a Feature)");
            }
        }
        JsonNode type = node.get("type");
        String t = type != null && type.isString() ? type.asString() : null;
        if (!ForecastRegion.Shape.POLYGON.equals(t) && !ForecastRegion.Shape.MULTI_POLYGON.equals(t)) {
            throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_REGION, ForecastRegion.PARAM,
                    "region as an object must be a GeoJSON Polygon or MultiPolygon");
        }
        JsonNode coordinates = node.get("coordinates");
        int[] budget = {limits.forecastPolygonVertices()};
        List<List<List<double[]>>> polygons = new ArrayList<>();
        if (ForecastRegion.Shape.POLYGON.equals(t)) {
            polygons.add(rings(coordinates, budget));
        } else {
            for (JsonNode polygon : array(coordinates)) {
                polygons.add(rings(polygon, budget));
            }
        }
        return ForecastRegion.Shape.of(t, polygons, limits.forecastPolygonVertices());
    }

    /** Rings of one polygon; counts positions against {@code budget} while reading. */
    private List<List<double[]>> rings(JsonNode polygon, int[] budget) {
        List<List<double[]>> rings = new ArrayList<>();
        for (JsonNode ring : array(polygon)) {
            List<double[]> positions = new ArrayList<>();
            for (JsonNode position : array(ring)) {
                if (--budget[0] < 0) {
                    throw new InvalidRequestException(ErrorCode.FORECAST_POLYGON_TOO_COMPLEX, ForecastRegion.PARAM,
                            "region polygon has more than " + limits.forecastPolygonVertices() + " positions");
                }
                List<JsonNode> values = array(position);
                double[] p = new double[values.size()];
                for (int i = 0; i < p.length; i++) {
                    p[i] = number(values.get(i));
                }
                positions.add(p);
            }
            rings.add(positions);
        }
        return rings;
    }

    private static List<JsonNode> array(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new InvalidRequestException(ErrorCode.INVALID_GEOMETRY, ForecastRegion.PARAM,
                    "region coordinates must be nested arrays as GeoJSON has them");
        }
        List<JsonNode> out = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            out.add(node.get(i));
        }
        return out;
    }

    /** A JSON number as a double; anything else (or a number a double cannot hold) is NaN, refused by the domain. */
    private static double number(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return Double.NaN;
        }
        try {
            return node.doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** The member, or null when it is absent or JSON null. */
    private static JsonNode present(JsonNode root, String name) {
        JsonNode n = root.get(name);
        return n == null || n.isNull() ? null : n;
    }
}
