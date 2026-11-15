package io.emberline.fireinfo;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import redis.embedded.RedisServer;

/** A local redis-server (bundled binary) standing in for redis-cache, plus a client to read the layer keys. */
final class LayerRedis {

    private final int port;
    private RedisServer server;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    LayerRedis() {
        try (ServerSocket s = new ServerSocket(0)) {
            this.port = s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    synchronized LayerRedis start() {
        try {
            server = RedisServer.newRedisServer().port(port).bind("127.0.0.1").setting("save \"\"")
                    .setting("appendonly no").setting("maxmemory-policy allkeys-lru").build();
            server.start();
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "redis://127.0.0.1:" + port;
    }

    synchronized String get(String key) {
        if (connection == null) {
            client = RedisClient.create(url());
            connection = client.connect();
        }
        return connection.sync().get(key);
    }

    synchronized void stop() {
        try {
            if (connection != null) {
                connection.close();
                client.shutdown();
            }
            if (server != null) {
                server.stop();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
