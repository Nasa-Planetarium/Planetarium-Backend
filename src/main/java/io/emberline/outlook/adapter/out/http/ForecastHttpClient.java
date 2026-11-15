package io.emberline.outlook.adapter.out.http;

import io.emberline.outlook.application.port.out.ForecastServicePort;
import io.emberline.outlook.application.service.ForecastProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.UpstreamException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The forecast service over the JDK HttpClient (HTTP/1.1 — plain http in the cluster, no h2c upgrade; redirects off).
 * Internal interface (contract/MAPPING.md §9.3): {@code POST /forecast} → 200 JSON object, or 400/404
 * {@code {"error", "message"}}; {@code GET /healthz} → {@code {as_of, data_version, warm}}. The success body is
 * returned as received; it is parsed only to check that it is a JSON object and to read its {@code as_of}. Bodies over
 * {@code max-response-bytes} are not read further. Anything else is {@code FORECAST_UNAVAILABLE} (503) and the
 * service body is never passed on or logged. One deadline ({@code timeout}) covers the headers and the body.
 */
@Component
class ForecastHttpClient implements ForecastServicePort {

    private static final Logger log = LoggerFactory.getLogger(ForecastHttpClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The service's refusals; any other status is "unavailable". */
    private static final Map<Integer, Outcome> REFUSALS = Map.of(400, Outcome.REJECTED, 404, Outcome.UNKNOWN_REGION);

    private final ForecastProperties properties;
    private final HttpClient http;
    private final URI forecast;
    private final URI healthz;

    ForecastHttpClient(ForecastProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(properties.connectTimeout()).build();
        String base = base(properties.url());
        this.forecast = URI.create(base + "/forecast");
        this.healthz = URI.create(base + "/healthz");
    }

    @Override
    public Status status() {
        Body r = send(HttpRequest.newBuilder(healthz).GET(), "/healthz");
        JsonNode node = r.status() == 200 ? object(r.bytes()) : null;
        if (node == null) {
            throw unavailable("/healthz", r.status());
        }
        JsonNode asOf = node.get("as_of");
        JsonNode dataVersion = node.get("data_version");
        JsonNode warm = node.get("warm");
        if (asOf == null || !asOf.isString() || dataVersion == null || !dataVersion.isString() || dataVersion.asString().isBlank()) {
            throw unavailable("/healthz", r.status());
        }
        try {
            return new Status(dataVersion.asString(), LocalDate.parse(asOf.asString()), warm != null && warm.isBoolean() && warm.asBoolean());
        } catch (DateTimeParseException e) {
            throw unavailable("/healthz", r.status());
        }
    }

    @Override
    public Reply forecast(String queryJson) {
        Body r = send(HttpRequest.newBuilder(forecast).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8)), "/forecast");
        JsonNode node = object(r.bytes());
        if (r.status() == 200 && node != null) {
            JsonNode asOf = node.get("as_of");
            return Reply.answered(r.bytes(), asOf != null && asOf.isString() ? asOf.asString() : null);
        }
        Outcome refusal = REFUSALS.get(r.status());
        if (refusal != null && node != null && node.get("error") != null && node.get("error").isString()) {
            JsonNode message = node.get("message");
            return Reply.refused(refusal, message != null && message.isString() ? message.asString() : null);
        }
        throw unavailable("/forecast", r.status());
    }

    /** Status and body (at most max-response-bytes; a longer body is "unavailable"). */
    private record Body(int status, byte[] bytes) {
    }

    private Body send(HttpRequest.Builder request, String what) {
        int max = properties.maxResponseBytes();
        CompletableFuture<HttpResponse<byte[]>> call = http.sendAsync(request.timeout(properties.timeout())
                .header("Accept", "application/json").header("User-Agent", "emberline-api").build(), info -> new LimitedBody(max));
        try {
            // the request timeout covers the wait for headers; this one also bounds a body that stalls
            HttpResponse<byte[]> response = call.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            return new Body(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("forecast service {} timeout", what);
            throw new UpstreamException(ErrorCode.FORECAST_UNAVAILABLE, "the forecast service did not answer in time");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                log.warn("forecast service {} timeout", what);
                throw new UpstreamException(ErrorCode.FORECAST_UNAVAILABLE, "the forecast service did not answer in time");
            }
            if (cause instanceof LimitedBody.TooLarge) {
                log.warn("forecast service {} answer over {} bytes", what, max);
                throw new UpstreamException(ErrorCode.FORECAST_UNAVAILABLE, "the forecast service is not answering normally");
            }
            log.warn("forecast service {} failure ({})", what, cause == null ? "?" : cause.getClass().getSimpleName());
            throw new UpstreamException(ErrorCode.FORECAST_UNAVAILABLE, "the forecast service cannot be reached");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new UpstreamException(ErrorCode.FORECAST_UNAVAILABLE, "the forecast request was interrupted");
        }
    }

    /** Collects at most {@code max} bytes; one byte more cancels the body and fails with {@link TooLarge}. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        static final class TooLarge extends IOException {
            TooLarge() {
                super("response body too large");
            }
        }

        private final int max;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int max) {
            this.max = max;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer b : items) {
                if (out.size() + (long) b.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLarge());
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    /** The body as a JSON object, or null when it is not one. */
    private static JsonNode object(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static UpstreamException unavailable(String what, int status) {
        log.warn("forecast service {} answered status {} with a body we do not accept", what, status);
        return new UpstreamException(ErrorCode.FORECAST_UNAVAILABLE, "the forecast service is not answering normally");
    }

    /** scheme://host[:port][/path] without query, fragment or user info; trailing slashes dropped. */
    static String base(String raw) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("emberline.forecast.url is not a URL");
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("emberline.forecast.url must be http(s)://host[:port][/path]");
        }
        return raw.replaceAll("/+$", "");
    }
}
