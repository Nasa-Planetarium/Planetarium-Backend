package io.emberline.harmonized.adapter.out;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code emberline_cache_requests_total{route,result}} (result hit · miss · error) and
 * {@code emberline_sql_statements_total{route}} (one per SQL statement a route sends to PostgreSQL).
 */
@Component
public class HarmonizedMetrics {

    private final MeterRegistry registry;

    public HarmonizedMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void sqlStatement(String route) {
        Counter.builder("emberline.sql.statements").description("SQL statements sent to PostgreSQL per route")
                .tag("route", route).register(registry).increment();
    }

    public void cache(String route, String result) {
        Counter.builder("emberline.cache.requests").description("redis-cache lookups per route and result")
                .tag("route", route).tag("result", result).register(registry).increment();
    }
}
