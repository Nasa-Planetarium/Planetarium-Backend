package io.emberline.platform.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.stereotype.Component;

/**
 * The redis-durable connection (noeviction). Kept apart from redis-cache: it is not a {@code RedisConnectionFactory}
 * bean, so Boot's cache factory stays the only one. Connections are opened lazily; while redis-durable is down,
 * commands fail fast (REJECT_COMMANDS) and a reconnect is attempted at most once per {@link #RETRY}.
 */
@Component
@EnableConfigurationProperties(EventsProperties.class)
public class DurableRedis implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DurableRedis.class);
    static final Duration RETRY = Duration.ofSeconds(2);

    private final EventsProperties properties;
    private final RedisClient client;
    private final Lazy<StatefulRedisConnection<String, String>> strings;
    private final Lazy<StatefulRedisConnection<byte[], byte[]>> bytes;
    private volatile LettuceConnectionFactory springFactory;

    public DurableRedis(EventsProperties properties) {
        this.properties = properties;
        RedisURI uri = RedisURI.create(properties.durableUrl());
        uri.setTimeout(properties.commandTimeout());
        this.client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                .timeoutOptions(TimeoutOptions.enabled(properties.commandTimeout()))
                .build());
        this.strings = new Lazy<>(() -> client.connect(StringCodec.UTF8));
        this.bytes = new Lazy<>(() -> client.connect((RedisCodec<byte[], byte[]>) ByteArrayCodec.INSTANCE));
    }

    public EventsProperties properties() {
        return properties;
    }

    /** String connection (streams, DLQ, metrics); empty while redis-durable cannot be reached. */
    public Optional<StatefulRedisConnection<String, String>> strings() {
        return strings.get();
    }

    /** byte[] connection (Bucket4j buckets); empty while redis-durable cannot be reached. */
    public Optional<StatefulRedisConnection<byte[], byte[]>> bytes() {
        return bytes.get();
    }

    /** A Spring Data factory on redis-durable for the StreamMessageListenerContainer (created on first use). */
    public synchronized RedisConnectionFactory springFactory() {
        if (springFactory == null) {
            RedisURI uri = RedisURI.create(properties.durableUrl());
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
            standalone.setDatabase(uri.getDatabase());
            if (uri.getPassword() != null) {
                standalone.setPassword(uri.getPassword());
            }
            LettuceConnectionFactory f = new LettuceConnectionFactory(standalone, LettuceClientConfiguration.builder()
                    .commandTimeout(properties.commandTimeout())
                    .clientOptions(ClientOptions.builder()
                            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                            .build())
                    .build());
            f.afterPropertiesSet();
            f.start();
            springFactory = f;
        }
        return springFactory;
    }

    @Override
    public void destroy() {
        strings.close();
        bytes.close();
        if (springFactory != null) {
            springFactory.destroy();
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
    }

    /** A connection opened on first use; a failed open is retried at most once per {@link #RETRY}. */
    static final class Lazy<C extends StatefulRedisConnection<?, ?>> {
        private final Supplier<C> open;
        private final AtomicLong nextTry = new AtomicLong();
        private volatile C connection;

        Lazy(Supplier<C> open) {
            this.open = open;
        }

        Optional<C> get() {
            C c = connection;
            if (c != null) {
                return Optional.of(c);
            }
            long now = System.nanoTime();
            long next = nextTry.get();
            if (next != 0 && now - next < 0) {
                return Optional.empty();
            }
            synchronized (this) {
                if (connection == null) {
                    try {
                        connection = open.get();
                    } catch (RuntimeException e) {
                        nextTry.set(System.nanoTime() + RETRY.toNanos());
                        log.warn("redis-durable is not reachable ({})", e.getClass().getSimpleName());
                        return Optional.empty();
                    }
                }
                return Optional.of(connection);
            }
        }

        void close() {
            C c = connection;
            if (c != null) {
                c.close();
            }
        }
    }
}
