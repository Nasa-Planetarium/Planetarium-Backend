package io.emberline.acceptance.s4limits;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import redis.embedded.RedisServer;

/** Two embedded redis-server 6.2 processes like the deployment: redis-cache (LRU, small maxmemory) and redis-durable (noeviction). */
final class Redises {

    final int cachePort = freePort();
    final int durablePort = freePort();
    private RedisServer cache;
    private RedisServer durable;

    Redises start() {
        cache = server(cachePort, "maxmemory 2mb", "maxmemory-policy allkeys-lru");
        durable = server(durablePort, "maxmemory 64mb", "maxmemory-policy noeviction");
        return this;
    }

    String cacheUrl() {
        return "redis://127.0.0.1:" + cachePort;
    }

    String durableUrl() {
        return "redis://127.0.0.1:" + durablePort;
    }

    void stopDurable() {
        try {
            durable.stop();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void startDurable() {
        durable = server(durablePort, "maxmemory 64mb", "maxmemory-policy noeviction");
    }

    void stop() {
        try {
            cache.stop();
            durable.stop();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RedisCommands<String, String> client(String url) {
        StatefulRedisConnection<String, String> c = RedisClient.create(url).connect();
        return c.sync();
    }

    private static RedisServer server(int port, String... settings) {
        try {
            var b = RedisServer.newRedisServer().port(port).bind("127.0.0.1").setting("save \"\"").setting("appendonly no");
            for (String s : settings) {
                b.setting(s);
            }
            RedisServer s = b.build();
            s.start();
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
