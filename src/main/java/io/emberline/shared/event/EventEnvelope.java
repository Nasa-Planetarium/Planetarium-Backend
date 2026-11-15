package io.emberline.shared.event;

import java.util.Objects;

/**
 * An integration event as it travels between pods (envelope). Handlers are {@code Consumer<EventEnvelope<T>>}
 * beans marked {@link StreamConsumer}; they never see transport types (Redis today, Kafka later).
 *
 * @param eventId id of the stored publication (platform.event_publication.id) — the dedup key of every inbox
 * @param type e.g. {@code release.activated}
 * @param schemaVersion payload schema version
 * @param occurredAt ISO-8601 instant of the publication
 * @param aggregateType e.g. {@code release}
 * @param aggregateId e.g. the release key
 * @param traceparent W3C trace context of the producer
 * @param producer application and host that published
 * @param payload the event record
 */
public record EventEnvelope<T>(String eventId, String type, String schemaVersion, String occurredAt, String aggregateType,
                               String aggregateId, String traceparent, String producer, T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(type);
    }
}
