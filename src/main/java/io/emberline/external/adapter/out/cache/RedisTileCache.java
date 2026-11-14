package io.emberline.external.adapter.out.cache;

import io.emberline.external.application.port.out.TileCachePort;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

/** Tiles on redis-cache (Boot's connection, EMBERLINE_REDIS_CACHE_URL). Errors are cache misses. */
@Component
class RedisTileCache implements TileCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisTileCache.class);

    private final RedisTemplate<String, byte[]> redis;

    RedisTileCache(RedisConnectionFactory cacheConnections) {
        RedisTemplate<String, byte[]> t = new RedisTemplate<>();
        t.setConnectionFactory(cacheConnections);
        t.setKeySerializer(RedisSerializer.string());
        t.setValueSerializer(RedisSerializer.byteArray());
        t.afterPropertiesSet();
        this.redis = t;
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException e) {
            log.warn("redis-cache read failed ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, byte[] png, Duration ttl) {
        try {
            redis.opsForValue().set(key, png, ttl);
        } catch (RuntimeException e) {
            log.warn("redis-cache write failed ({})", e.getClass().getSimpleName());
        }
    }
}
