package io.emberline.platform.events;

import io.emberline.ingest.api.ReleaseActivated;
import io.emberline.platform.redis.DurableRedis;
import io.emberline.platform.redis.EventsProperties;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import io.emberline.shared.sql.SqlResource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transport end of {@code release.activated} (emberline.events.transport=redis): the Modulith publication registry
 * (schema platform) stores the event in the activation transaction; after commit this listener XADDs the envelope to
 * {@code em:{env}:evt:release} with {@code eventId} = the stored publication's id. A failed XADD returns a failed
 * future: the publication stays incomplete (activation itself is not affected) and is republished on the next start
 * ({@code republish-outstanding-events-on-restart}) with the same eventId. Pods also poll the release pointer.
 */
@Component
class RedisStreamsExternalizer {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsExternalizer.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DurableRedis redis;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String producer;
    private volatile String listenerId;

    /** A stored publication: its id is the envelope's eventId, its date the occurredAt. */
    record Publication(UUID id, Instant publishedAt) {
    }

    RedisStreamsExternalizer(DurableRedis redis, JdbcClient jdbc, JsonMapper json,
                             @Value("${spring.application.name:emberline}") String application) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.json = json;
        this.producer = application + "@" + host();
    }

    @TransactionalEventListener
    CompletableFuture<Void> on(ReleaseActivated event) {
        EventsProperties p = redis.properties();
        log.info("release.activated release_id={} release_key={} method_version={} data_version={} previous={}",
                event.releaseId(), event.releaseKey(), event.methodVersion(), event.dataVersion(), event.previousReleaseId());
        if (!p.redisTransport()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Optional<Publication> publication = publication(event);
            if (publication.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("publication of release.activated not found"));
            }
            StatefulRedisConnection<String, String> c = redis.strings()
                    .orElseThrow(() -> new IllegalStateException("redis-durable is not reachable"));
            Map<String, String> fields = envelope(publication.get(), event);
            String id = c.sync().xadd(p.releaseStream(), XAddArgs.Builder.maxlen(p.streamMaxLen()).approximateTrimming(), fields);
            log.info("release.activated XADD stream={} entry={} event_id={}", p.releaseStream(), id, fields.get("eventId"));
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            log.warn("release.activated not externalized ({}); it stays incomplete and is republished on restart",
                    e.getClass().getSimpleName());
            return CompletableFuture.failedFuture(e);
        }
    }

    Map<String, String> envelope(Publication publication, ReleaseActivated event) {
        EventTypes.Type type = EventTypes.RELEASE_ACTIVATED;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", publication.id().toString());
        fields.put("type", type.name());
        fields.put("schemaVersion", type.schemaVersion());
        fields.put("occurredAt", publication.publishedAt().toString());
        fields.put("aggregateType", type.aggregateType());
        fields.put("aggregateId", event.releaseKey());
        fields.put("traceparent", "00-" + hex(16) + "-" + hex(8) + "-01");
        fields.put("producer", producer);
        fields.put("payload", json.writeValueAsString(event));
        return fields;
    }

    /** The oldest incomplete publication of this event for this listener (platform.event_publication). */
    private Optional<Publication> publication(ReleaseActivated event) {
        return jdbc.sql(SqlResource.text("platform/select_incomplete_publications.sql"))
                .param("listenerId", listenerId()).param("eventType", ReleaseActivated.class.getName())
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class),
                        rs.getObject("publication_date", OffsetDateTime.class).toInstant(), rs.getString("serialized_event")))
                .list().stream()
                .filter(c -> same(c.serialized(), event))
                .findFirst()
                .map(c -> new Publication(c.id(), c.publishedAt()));
    }

    private record Candidate(UUID id, Instant publishedAt, String serialized) {
    }

    /** The stored JSON is the same event (field names camelCase or snake_case, whichever the serializer used). */
    private boolean same(String serialized, ReleaseActivated e) {
        JsonNode n = json.readTree(serialized);
        return e.releaseId() == field(n, "releaseId", "release_id").asLong(-1)
                && e.releaseKey().equals(field(n, "releaseKey", "release_key").asString(""))
                && e.dataVersion().equals(field(n, "dataVersion", "data_version").asString(""))
                && e.methodVersion().equals(field(n, "methodVersion", "method_version").asString(""))
                && java.util.Objects.equals(e.previousReleaseId(), optionalLong(field(n, "previousReleaseId", "previous_release_id")));
    }

    private static Long optionalLong(JsonNode v) {
        return v == null || v.isNull() || v.isMissingNode() ? null : v.asLong();
    }

    private static JsonNode field(JsonNode n, String camel, String snake) {
        JsonNode v = n.get(camel);
        return v != null ? v : n.path(snake);
    }

    private String listenerId() {
        if (listenerId == null) {
            try {
                Method m = RedisStreamsExternalizer.class.getDeclaredMethod("on", ReleaseActivated.class);
                listenerId = new TransactionalApplicationListenerMethodAdapter("relay", RedisStreamsExternalizer.class, m).getListenerId();
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
        return listenerId;
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }
}
