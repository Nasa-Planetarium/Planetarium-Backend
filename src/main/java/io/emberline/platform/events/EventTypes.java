package io.emberline.platform.events;

import io.emberline.ingest.api.ReleaseActivated;
import java.util.Map;
import java.util.Optional;

/** Integration event types: envelope {@code type} ↔ payload record, aggregate type and payload schema version. */
final class EventTypes {

    record Type(String name, Class<?> payload, String aggregateType, String schemaVersion) {
    }

    static final Type RELEASE_ACTIVATED = new Type("release.activated", ReleaseActivated.class, "release", "1");
    private static final Map<String, Type> BY_NAME = Map.of(RELEASE_ACTIVATED.name(), RELEASE_ACTIVATED);

    private EventTypes() {
    }

    static Optional<Type> byName(String name) {
        return Optional.ofNullable(name).map(BY_NAME::get);
    }
}
