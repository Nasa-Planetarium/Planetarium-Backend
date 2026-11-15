package io.emberline.platform.ratelimit;

import io.emberline.platform.api.BudgetDecision;
import io.emberline.platform.api.SharedBudgetApi;
import io.emberline.platform.redis.DurableRedis;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Token buckets on redis-durable (Bucket4j + Lettuce, key expiry = time to refill up to capacity). When
 * redis-durable cannot be used, the same key falls back to a pod-local bucket for {@link #DEGRADED} before Redis is
 * tried again — public reads keep working, limits become per pod. {@link #takeLocal} buckets are always pod-local
 * (scope global: one hot key on redis would serialise every request of every caller).
 */
@Component
public class TokenBuckets implements SharedBudgetApi {

    private static final Logger log = LoggerFactory.getLogger(TokenBuckets.class);
    static final Duration DEGRADED = Duration.ofSeconds(5);
    private static final int LOCAL_MAX = 100_000;

    private final DurableRedis redis;
    private final ClientKeyResolver clients;
    private final RateLimitProperties properties;
    private final TimeMeter clock;
    private final Map<String, Bucket> local = new ConcurrentHashMap<>();
    private final Map<String, Bucket> podLocal = new ConcurrentHashMap<>();
    private final AtomicLong degradedUntil = new AtomicLong();
    private volatile ProxyManager<byte[]> proxies;

    @Autowired
    public TokenBuckets(DurableRedis redis, ClientKeyResolver clients, RateLimitProperties properties) {
        this(redis, clients, properties, TimeMeter.SYSTEM_NANOTIME);
    }

    /** {@code redis} null = pod-local buckets only, on {@code clock} (simulations). */
    public TokenBuckets(DurableRedis redis, ClientKeyResolver clients, RateLimitProperties properties, TimeMeter clock) {
        this.redis = redis;
        this.clients = clients;
        this.properties = properties;
        this.clock = clock;
    }

    /** One token check: granted, or the nanoseconds to wait. */
    public record Result(boolean granted, long nanosToWait) {

        public long retryAfterSeconds() {
            return Math.max(1, (nanosToWait + 999_999_999L) / 1_000_000_000L);
        }
    }

    public Result take(String key, long cost, long capacity, long refillTokens, Duration period) {
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(Math.min(refillTokens, capacity), period).build())
                .build();
        Optional<ProxyManager<byte[]>> remote = remote();
        if (remote.isPresent()) {
            try {
                return result(remote.get().builder().build(key.getBytes(StandardCharsets.UTF_8), () -> config)
                        .tryConsumeAndReturnRemaining(cost));
            } catch (RuntimeException e) {
                degradedUntil.set(System.nanoTime() + DEGRADED.toNanos());
                log.warn("rate-limit buckets on redis-durable unavailable ({}); using pod-local buckets", e.getClass().getSimpleName());
            }
        }
        return local(local, key, config, cost);
    }

    /** Like {@link #take} on a bucket of this pod only (never redis). */
    public Result takeLocal(String key, long cost, long capacity, long refillTokens, Duration period) {
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(Math.min(refillTokens, capacity), period).build())
                .build();
        return local(podLocal, key, config, cost);
    }

    private Result local(Map<String, Bucket> buckets, String key, BucketConfiguration config, long cost) {
        if (buckets.size() > LOCAL_MAX) {
            buckets.clear();
        }
        return result(buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(config.getBandwidths()[0])
                .withCustomTimePrecision(clock).build()).tryConsumeAndReturnRemaining(cost));
    }

    @Override
    public BudgetDecision tryTake(String name, long capacity, Duration period) {
        Result r = take(budgetKey(name), 1, capacity, capacity, period);
        return new BudgetDecision(r.granted(), r.granted() ? 0 : r.retryAfterSeconds());
    }

    @Override
    public BudgetDecision tryTakeForCaller(String name, long capacity, Duration period) {
        if (!properties.enabled() || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return new BudgetDecision(true, 0);
        }
        Result r = take(budgetKey(name + ":caller:" + clients.key(attributes.getRequest())), 1, capacity, capacity, period);
        return new BudgetDecision(r.granted(), r.granted() ? 0 : r.retryAfterSeconds());
    }

    private String budgetKey(String suffix) {
        return redis == null ? "budget:" + suffix : redis.properties().key("budget:" + suffix);
    }

    private Optional<ProxyManager<byte[]>> remote() {
        if (redis == null) {
            return Optional.empty();
        }
        long until = degradedUntil.get();
        if (until != 0 && System.nanoTime() - until < 0) {
            return Optional.empty();
        }
        if (proxies != null) {
            return Optional.of(proxies);
        }
        synchronized (this) {
            if (proxies == null) {
                redis.bytes().ifPresent(c -> proxies = Bucket4jLettuce.casBasedBuilder(c)
                        .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                        .requestTimeout(Duration.ofSeconds(1))
                        .build());
            }
            if (proxies == null) {
                degradedUntil.set(System.nanoTime() + DEGRADED.toNanos());
            }
            return Optional.ofNullable(proxies);
        }
    }

    private static Result result(ConsumptionProbe probe) {
        return new Result(probe.isConsumed(), probe.getNanosToWaitForRefill());
    }
}
