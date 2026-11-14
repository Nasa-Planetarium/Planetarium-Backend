package io.emberline.shared.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code Consumer<EventEnvelope<T>>} bean as a stream consumer. {@code broadcast = true}: every pod gets every
 * entry (no group, no inbox — the handler must be idempotent); otherwise one pod of the consumer group gets it once
 * (inbox by eventId, DLQ after repeated failures).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface StreamConsumer {

    /** Consumer name (group name for group consumers), e.g. {@code cache-warmer}. */
    String value();

    boolean broadcast() default false;
}
