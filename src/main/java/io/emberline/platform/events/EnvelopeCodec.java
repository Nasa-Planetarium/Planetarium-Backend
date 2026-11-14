package io.emberline.platform.events;

import io.emberline.shared.event.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Stream entry fields ↔ {@link EventEnvelope}. Anything malformed is a {@link BadEnvelope} (→ DLQ at once). */
@Component
class EnvelopeCodec {

    static final class BadEnvelope extends RuntimeException {
        BadEnvelope(String reason) {
            super(reason, null, false, false);
        }
    }

    private final JsonMapper json;

    EnvelopeCodec(JsonMapper json) {
        this.json = json;
    }

    EventEnvelope<Object> decode(Map<String, String> fields) {
        String eventId = fields.get("eventId");
        if (eventId == null) {
            throw new BadEnvelope("eventId missing");
        }
        try {
            UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            throw new BadEnvelope("eventId is not a UUID");
        }
        EventTypes.Type type = EventTypes.byName(fields.get("type")).orElseThrow(() -> new BadEnvelope("unknown type"));
        Object payload;
        try {
            payload = json.readValue(fields.getOrDefault("payload", ""), type.payload());
        } catch (RuntimeException e) {
            throw new BadEnvelope("payload cannot be deserialized");
        }
        if (payload == null) {
            throw new BadEnvelope("payload missing");
        }
        return new EventEnvelope<>(eventId, type.name(), fields.get("schemaVersion"), fields.get("occurredAt"),
                fields.get("aggregateType"), fields.get("aggregateId"), fields.get("traceparent"), fields.get("producer"), payload);
    }
}
