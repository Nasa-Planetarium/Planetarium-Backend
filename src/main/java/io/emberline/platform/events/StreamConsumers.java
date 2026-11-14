package io.emberline.platform.events;

import io.emberline.platform.redis.DurableRedis;
import io.emberline.platform.redis.EventsProperties;
import io.emberline.shared.event.EventEnvelope;
import io.emberline.shared.event.StreamConsumer;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.models.stream.PendingMessage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.ResolvableType;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Consumers of {@code em:{env}:evt:release} in API pods. Group consumers ({@link StreamConsumer}, broadcast=false)
 * read with a StreamMessageListenerContainer under their group: undecodable entry or eventId not in
 * platform.event_publication → {@code em:{env}:dlq:release} + ACK at once; otherwise InboxGuard runs the handler
 * once per eventId, then ACK; a failing handler leaves the entry pending, PendingReclaimer (XAUTOCLAIM every
 * {@code reclaim-interval}, idle ≥ {@code min-idle}) retries it and after {@code max-deliveries} moves it to the DLQ.
 * Broadcast consumers (no group) get every new entry in every pod; invalid entries are skipped there (the group
 * consumers own the DLQ). Gauges emberline_events_lag{stream,group} and emberline_dlq_size{stream}.
 */
@Component
@ConditionalOnWebApplication
class StreamConsumers implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumers.class);

    /** A handler bean with its consumer name, mode and payload type. */
    record Handler(String name, boolean broadcast, Class<?> payload, java.util.function.Consumer<EventEnvelope<Object>> consumer) {
    }

    private final DurableRedis redis;
    private final EventsProperties properties;
    private final EnvelopeCodec codec;
    private final EnvelopeVerifier verifier;
    private final InboxGuard inbox;
    private final ApplicationContext context;
    private final MeterRegistry meters;
    private final String me = "c-" + UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, AtomicLong> lag = new LinkedHashMap<>();
    private final AtomicLong dlqSize = new AtomicLong();
    private List<Handler> handlers = List.of();
    private ScheduledExecutorService scheduler;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean running;
    private volatile long lastReclaim;

    StreamConsumers(DurableRedis redis, EnvelopeCodec codec, EnvelopeVerifier verifier, InboxGuard inbox,
                    ApplicationContext context, MeterRegistry meters) {
        this.redis = redis;
        this.properties = redis.properties();
        this.codec = codec;
        this.verifier = verifier;
        this.inbox = inbox;
        this.context = context;
        this.meters = meters;
    }

    @Override
    public void start() {
        running = true;
        if (!properties.consumersEnabled() || !properties.redisTransport()) {
            return;
        }
        handlers = discover();
        String stream = properties.releaseStream();
        handlers.stream().filter(h -> !h.broadcast()).forEach(h -> {
            AtomicLong value = new AtomicLong();
            lag.put(h.name(), value);
            Gauge.builder("emberline.events.lag", value, AtomicLong::get).tag("stream", stream).tag("group", h.name()).register(meters);
        });
        Gauge.builder("emberline.dlq.size", dlqSize, AtomicLong::get).tag("stream", stream).register(meters);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "emberline-stream-consumers");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @SuppressWarnings("unchecked")
    private List<Handler> discover() {
        List<Handler> out = new ArrayList<>();
        context.getBeansWithAnnotation(StreamConsumer.class).values().forEach(bean -> {
            Class<?> type = AopUtils.getTargetClass(bean);
            StreamConsumer a = type.getAnnotation(StreamConsumer.class);
            Class<?> payload = ResolvableType.forClass(type).as(java.util.function.Consumer.class).getGeneric(0).getGeneric(0).resolve();
            if (a == null || payload == null || !(bean instanceof java.util.function.Consumer<?>)) {
                throw new IllegalStateException("@StreamConsumer beans must implement Consumer<EventEnvelope<T>>");
            }
            out.add(new Handler(a.value(), a.broadcast(), payload, (java.util.function.Consumer<EventEnvelope<Object>>) bean));
        });
        return out;
    }

    /** Starts the readers once redis-durable is reachable; reclaims pending entries and refreshes the gauges. */
    void tick() {
        try {
            if (container == null) {
                startContainer();
            }
            long now = System.nanoTime();
            if (container != null && now - lastReclaim >= properties.reclaimInterval().toNanos()) {
                lastReclaim = now;
                reclaim();
                gauges();
            }
        } catch (RuntimeException e) {
            log.debug("stream consumers tick failed ({})", e.getClass().getSimpleName());
        }
    }

    private void startContainer() {
        RedisCommands<String, String> cmd = redis.strings().map(c -> c.sync()).orElse(null);
        if (cmd == null) {
            return;
        }
        String stream = properties.releaseStream();
        for (Handler h : handlers) {
            if (!h.broadcast()) {
                try {
                    cmd.xgroupCreate(XReadArgs.StreamOffset.from(stream, "0"), h.name(), XGroupCreateArgs.Builder.mkstream());
                } catch (RedisBusyException exists) {
                    // group already there
                }
            }
        }
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(500)).batchSize(16)
                        .errorHandler(t -> log.debug("stream read failed ({})", t.getClass().getSimpleName()))
                        .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> c =
                StreamMessageListenerContainer.create(redis.springFactory(), options);
        for (Handler h : handlers) {
            if (h.broadcast()) {
                c.register(StreamMessageListenerContainer.StreamReadRequest.builder(StreamOffset.create(stream, ReadOffset.latest()))
                        .cancelOnError(t -> false).build(), m -> broadcast(h, m.getValue()));
            } else {
                c.register(StreamMessageListenerContainer.StreamReadRequest.builder(StreamOffset.create(stream, ReadOffset.lastConsumed()))
                        .consumer(org.springframework.data.redis.connection.stream.Consumer.from(h.name(), me))
                        .autoAcknowledge(false).cancelOnError(t -> false).build(),
                        m -> group(h, m.getId().getValue(), m.getValue()));
            }
        }
        c.start();
        container = c;
        log.info("stream consumers started on {} ({})", stream, handlers.stream().map(Handler::name).toList());
    }

    private void broadcast(Handler h, Map<String, String> fields) {
        try {
            EventEnvelope<Object> envelope = codec.decode(fields);
            if (h.payload().isInstance(envelope.payload()) && verifier.isStoredPublication(envelope.eventId())) {
                h.consumer().accept(envelope);
            }
        } catch (RuntimeException e) {
            log.warn("{} skipped an entry ({})", h.name(), e.getClass().getSimpleName());
        }
    }

    /** Group delivery: DLQ for bad entries, inbox + handler, then ACK; a handler failure leaves the entry pending. */
    void group(Handler h, String entryId, Map<String, String> fields) {
        RedisCommands<String, String> cmd = redis.strings().map(c -> c.sync()).orElse(null);
        if (cmd == null) {
            return;
        }
        String stream = properties.releaseStream();
        EventEnvelope<Object> envelope;
        try {
            envelope = codec.decode(fields);
        } catch (EnvelopeCodec.BadEnvelope e) {
            deadLetter(cmd, h, entryId, fields, e.getMessage());
            return;
        }
        if (!h.payload().isInstance(envelope.payload())) {
            cmd.xack(stream, h.name(), entryId);
            return;
        }
        if (!verifier.isStoredPublication(envelope.eventId())) {
            deadLetter(cmd, h, entryId, fields, "eventId is not a stored publication");
            return;
        }
        try {
            boolean ran = inbox.once(h.name(), envelope.eventId(), () -> h.consumer().accept(envelope));
            cmd.xack(stream, h.name(), entryId);
            log.info("{} {} event_id={} entry={}", h.name(), ran ? "handled" : "skipped duplicate", envelope.eventId(), entryId);
        } catch (RuntimeException e) {
            log.warn("{} failed on entry {} ({}); left pending", h.name(), entryId, e.getClass().getSimpleName());
        }
    }

    private void deadLetter(RedisCommands<String, String> cmd, Handler h, String entryId, Map<String, String> fields, String reason) {
        Map<String, String> dead = new LinkedHashMap<>(fields);
        dead.put("dlqReason", reason);
        dead.put("dlqConsumer", h.name());
        dead.put("dlqEntryId", entryId);
        cmd.xadd(properties.releaseDlq(), XAddArgs.Builder.maxlen(properties.streamMaxLen()).approximateTrimming(), dead);
        cmd.xack(properties.releaseStream(), h.name(), entryId);
        log.warn("{} moved entry {} to {} ({})", h.name(), entryId, properties.releaseDlq(), reason);
    }

    /** PendingReclaimer: too many deliveries → DLQ; otherwise XAUTOCLAIM idle entries and handle them again. */
    void reclaim() {
        RedisCommands<String, String> cmd = redis.strings().map(c -> c.sync()).orElse(null);
        if (cmd == null) {
            return;
        }
        String stream = properties.releaseStream();
        long minIdle = properties.minIdle().toMillis();
        for (Handler h : handlers) {
            if (h.broadcast()) {
                continue;
            }
            for (PendingMessage p : cmd.xpending(stream, h.name(), Range.create("-", "+"), Limit.from(100))) {
                if (p.getMsSinceLastDelivery() >= minIdle && p.getRedeliveryCount() >= properties.maxDeliveries()) {
                    List<StreamMessage<String, String>> entry = cmd.xrange(stream, Range.create(p.getId(), p.getId()));
                    deadLetter(cmd, h, p.getId(), entry.isEmpty() ? Map.of() : entry.get(0).getBody(),
                            "delivered " + p.getRedeliveryCount() + " times");
                }
            }
            ClaimedMessages<String, String> claimed = cmd.xautoclaim(stream,
                    XAutoClaimArgs.Builder.xautoclaim(Consumer.from(h.name(), me), properties.minIdle(), "0-0").count(100));
            for (StreamMessage<String, String> m : claimed.getMessages()) {
                group(h, m.getId(), m.getBody());
            }
        }
    }

    void gauges() {
        RedisCommands<String, String> cmd = redis.strings().map(c -> c.sync()).orElse(null);
        if (cmd == null) {
            return;
        }
        String stream = properties.releaseStream();
        dlqSize.set(cmd.xlen(properties.releaseDlq()));
        for (Object o : cmd.xinfoGroups(stream)) {
            Map<String, Object> g = pairs(o);
            AtomicLong value = lag.get(String.valueOf(g.get("name")));
            if (value != null) {
                long pending = g.get("pending") instanceof Long l ? l : 0;
                String last = String.valueOf(g.get("last-delivered-id"));
                long undelivered = cmd.xrange(stream, Range.create("(" + last, "+"), Limit.from(10_000)).size();
                value.set(pending + undelivered);
            }
        }
    }

    private static Map<String, Object> pairs(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (o instanceof List<?> list) {
            for (int i = 0; i + 1 < list.size(); i += 2) {
                out.put(String.valueOf(list.get(i)), list.get(i + 1));
            }
        }
        return out;
    }

    /** Test hook: run one reclaim + gauge pass now. */
    void reclaimNow() {
        reclaim();
        gauges();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
