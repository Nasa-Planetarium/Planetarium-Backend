package io.emberline.platform.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code emberline.events.*} — event transport and the redis-durable connection (noeviction: Streams, rate-limit
 * buckets, global budgets). Keys are {@code em:{env}:...}.
 *
 * @param transport        {@code redis} (Streams) or {@code log} (relay only logs; pods still poll the release)
 * @param env              key namespace, e.g. {@code test}, {@code prod}
 * @param durableUrl       redis-durable URL (EMBERLINE_REDIS_DURABLE_URL)
 * @param consumersEnabled stream consumers run in API pods only (off for the flyway / ingest profiles)
 * @param reclaimInterval  how often pending entries are reclaimed (XAUTOCLAIM)
 * @param minIdle          idle time before a pending entry is reclaimed
 * @param maxDeliveries    deliveries after which an entry goes to the DLQ
 * @param streamMaxLen     approximate stream cap (XADD MAXLEN ~)
 * @param commandTimeout   redis-durable command timeout
 */
@ConfigurationProperties("emberline.events")
public record EventsProperties(String transport, String env, String durableUrl, Boolean consumersEnabled,
                               Duration reclaimInterval, Duration minIdle, Integer maxDeliveries, Long streamMaxLen,
                               Duration commandTimeout) {

    public EventsProperties {
        transport = transport == null || transport.isBlank() ? "redis" : transport;
        env = env == null || !env.matches("^[a-z0-9-]{1,32}$") ? "test" : env;
        durableUrl = durableUrl == null || durableUrl.isBlank() ? "redis://localhost:6380" : durableUrl;
        consumersEnabled = consumersEnabled == null || consumersEnabled;
        reclaimInterval = reclaimInterval == null ? Duration.ofSeconds(30) : reclaimInterval;
        minIdle = minIdle == null ? Duration.ofSeconds(60) : minIdle;
        maxDeliveries = maxDeliveries == null || maxDeliveries < 1 ? 5 : maxDeliveries;
        streamMaxLen = streamMaxLen == null || streamMaxLen < 100 ? 10_000L : streamMaxLen;
        commandTimeout = commandTimeout == null ? Duration.ofSeconds(2) : commandTimeout;
    }

    public String key(String suffix) {
        return "em:" + env + ":" + suffix;
    }

    public String releaseStream() {
        return key("evt:release");
    }

    public String releaseDlq() {
        return key("dlq:release");
    }

    public boolean redisTransport() {
        return "redis".equals(transport);
    }
}
