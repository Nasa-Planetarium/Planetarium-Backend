package io.emberline.b2;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.support.Duck;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Expected values straight from the fixture files, a Redis client for key counts, number/null assertions. */
final class B2Support {

    private B2Support() {
    }

    /** aoi_month LEFT JOIN aoi_month_compare row of the fixture, columns in a fixed order (numbers as DOUBLE/BIGINT). */
    static List<List<Object>> monthRows(Path dir, boolean v11, String aoiId, int fromYear, int toYear) {
        return Duck.query("SELECT m.year::INTEGER, m.month::INTEGER, m.value, m.value_lo, m.value_hi, m.raw_modis::BIGINT, "
                + "m.raw_viirs::BIGINT, m.value_source, m.data_source, m.status, m.n_cells::BIGINT, c.verdict, c.percentile, "
                + "c.ratio_to_median, c.low_sample, " + (v11 ? "m.value_projected, CAST(m.as_of_date AS VARCHAR)" : "NULL::DOUBLE, NULL::VARCHAR")
                + " FROM read_parquet(?) m LEFT JOIN read_parquet(?) c USING (aoi_id, year, month) "
                + "WHERE aoi_id = ? AND m.year BETWEEN ? AND ? ORDER BY 1, 2",
                dir.resolve("aoi_month.parquet"), dir.resolve("aoi_month_compare.parquet"), aoiId, fromYear, toYear);
    }

    static void num(JsonNode node, Object expected, String what) {
        if (expected == null) {
            assertThat(node.isNull()).as(what + " is null").isTrue();
        } else {
            assertThat(node.isNumber()).as(what + " is a number").isTrue();
            assertThat(node.doubleValue()).as(what).isEqualTo(((Number) expected).doubleValue());
        }
    }

    static void text(JsonNode node, Object expected, String what) {
        if (expected == null) {
            assertThat(node.isNull()).as(what + " is null").isTrue();
        } else {
            assertThat(node.asString()).as(what).isEqualTo(expected.toString());
        }
    }

    /** in:name of every parameter of the contract's GET operation at {@code path} (refs resolved). */
    @SuppressWarnings("unchecked")
    static java.util.Set<String> contractParams(String path) {
        java.util.Map<String, Object> op = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) ((java.util.Map<String, Object>)
                io.emberline.support.Contract.openapi().get("paths")).get(path)).get("get");
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Object o : (List<Object>) op.getOrDefault("parameters", List.of())) {
            java.util.Map<String, Object> p = (java.util.Map<String, Object>) o;
            if (p.containsKey("$ref")) {
                String name = ((String) p.get("$ref")).substring("#/components/parameters/".length());
                p = (java.util.Map<String, Object>) io.emberline.support.Contract.at("components", "parameters").get(name);
            }
            out.add(p.get("in") + ":" + p.get("name"));
        }
        return out;
    }

    /** Redis client for the test's redis-cache. */
    static final class Keys implements AutoCloseable {
        private final RedisClient client;
        private final StatefulRedisConnection<String, String> conn;

        Keys(String url) {
            client = RedisClient.create(url);
            conn = client.connect();
        }

        long count(String fragment) {
            return conn.sync().keys("*" + fragment + "*").size();
        }

        List<String> keys(String fragment) {
            return conn.sync().keys("*" + fragment + "*");
        }

        void flush() {
            conn.sync().flushall();
        }

        @Override
        public void close() {
            conn.close();
            client.shutdown();
        }
    }
}
