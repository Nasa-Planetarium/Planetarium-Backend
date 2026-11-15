package io.emberline.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local stand-in for the forecast service (MAPPING §9.3): {@code GET /healthz} → {@code {as_of, data_version,
 * warm}}, {@code POST /forecast} → whatever {@link #answer} says. Records each forecast body it receives.
 */
public final class ForecastStub {

    /** One canned answer: status, content type, body bytes, delay before answering; {@code drop} closes without answering. */
    public record Answer(int status, String contentType, byte[] body, long delayMillis, boolean drop) {

        public static Answer json(int status, String body) {
            return new Answer(status, "application/json", body.getBytes(StandardCharsets.UTF_8), 0, false);
        }

        public static Answer raw(int status, String contentType, byte[] body) {
            return new Answer(status, contentType, body, 0, false);
        }

        public static Answer slow(long delayMillis, String body) {
            return new Answer(200, "application/json", body.getBytes(StandardCharsets.UTF_8), delayMillis, false);
        }

        public static Answer dropped() {
            return new Answer(0, null, new byte[0], 0, true);
        }
    }

    private final HttpServer server;
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public final AtomicInteger healthCalls = new AtomicInteger();
    public volatile Answer answer = Answer.json(200, "{}");
    public volatile Answer health = Answer.json(200, health("2026-11-14", "planner-2026-11-14#abc"));

    public ForecastStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/healthz", ex -> {
            healthCalls.incrementAndGet();
            respond(ex, health);
        });
        server.createContext("/forecast", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, answer);
        });
        server.start();
    }

    public static String health(String asOf, String dataVersion) {
        return "{\"as_of\": \"" + asOf + "\", \"data_version\": \"" + dataVersion + "\", \"warm\": true}";
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int calls() {
        return bodies.size();
    }

    public void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, Answer a) throws IOException {
        try {
            if (a.delayMillis() > 0) {
                Thread.sleep(a.delayMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (a.drop()) {
            ex.close();
            return;
        }
        if (a.contentType() != null) {
            ex.getResponseHeaders().set("Content-Type", a.contentType());
        }
        ex.sendResponseHeaders(a.status(), a.body().length == 0 ? -1 : a.body().length);
        if (a.body().length > 0) {
            ex.getResponseBody().write(a.body());
        }
        ex.close();
    }
}
