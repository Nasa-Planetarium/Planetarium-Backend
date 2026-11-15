package io.emberline.acceptance.s2read;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import redis.embedded.RedisServer;

/** A local redis-server (bundled binary) standing in for redis-cache; can be stopped and started again. */
final class EmbeddedRedis {

    private final int port;
    private RedisServer server;

    EmbeddedRedis() {
        try (ServerSocket s = new ServerSocket(0)) {
            this.port = s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    synchronized EmbeddedRedis start() {
        try {
            server = RedisServer.newRedisServer().port(port).bind("127.0.0.1").setting("save \"\"")
                    .setting("appendonly no").setting("maxmemory-policy allkeys-lru").build();
            server.start();
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    synchronized void stop() {
        try {
            if (server != null) {
                server.stop();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "redis://127.0.0.1:" + port;
    }
}
