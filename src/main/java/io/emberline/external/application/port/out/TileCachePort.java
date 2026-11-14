package io.emberline.external.application.port.out;

import java.time.Duration;
import java.util.Optional;

/** Tiles on redis-cache (LRU). A cache that cannot be reached is a miss, never an error. */
public interface TileCachePort {

    Optional<byte[]> get(String key);

    void put(String key, byte[] png, Duration ttl);
}
