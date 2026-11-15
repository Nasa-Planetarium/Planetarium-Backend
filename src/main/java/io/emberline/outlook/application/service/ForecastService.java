package io.emberline.outlook.application.service;

import io.emberline.outlook.application.port.in.ForecastQuery;
import io.emberline.outlook.application.port.in.ForecastUseCase;
import io.emberline.outlook.application.port.out.ForecastServicePort;
import io.emberline.outlook.application.port.out.ForecastServicePort.Outcome;
import io.emberline.outlook.application.port.out.ForecastServicePort.Reply;
import io.emberline.outlook.application.port.out.ForecastServicePort.Status;
import io.emberline.outlook.domain.ForecastRegion;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.error.EmberlineException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.error.ResourceNotFoundException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/forecast: the model's FireOutlook Planner answer, passed through. Values are the model's — this
 * class never reads or recomputes them (재구현·값 재계산 금지).
 *
 * <p>Cache (contract/MAPPING.md §9.5): key = the service's data_version and as_of ({@code /healthz}, reused for
 * {@code emberline.forecast.status-ttl}) + sha256 of the canonical query, plus the KST date for {@code horizon_days}
 * queries (the model counts those from "today" KST). An answer is stored only when the service answered from the same
 * as_of the key names and, for horizon_days, when the KST date did not change around the call; it expires after
 * {@code emberline.forecast.cache-ttl}. Without a {@code /healthz} answer nothing is read or stored — the query is
 * still forwarded. A redis-cache failure is a miss (shared layer cache).
 */
@Service
@EnableConfigurationProperties(ForecastProperties.class)
class ForecastService implements ForecastUseCase {

    static final LayerRoute ROUTE = LayerRoute.FORECAST;
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);
    /** Service messages reach the client only without paths, dotted class names, control characters, and ≤ 300 chars. */
    private static final Pattern UNSAFE = Pattern.compile("(?s).*([A-Za-z_$][\\w$]*\\.){2,}[A-Za-z_$].*|.*[/\\\\].*|.*\\p{Cntrl}.*");
    private static final int MAX_DETAIL = 300;

    /** The service's refusals as our problems (MAPPING §9.4). */
    private static final Map<Outcome, Function<String, EmberlineException>> REFUSALS = Map.of(
            Outcome.REJECTED, m -> new InvalidRequestException(ErrorCode.FORECAST_REJECTED, null,
                    detail(m, "the forecast service cannot answer this query")),
            Outcome.UNKNOWN_REGION, m -> new ResourceNotFoundException(ErrorCode.FORECAST_UNKNOWN_REGION, ForecastRegion.PARAM,
                    detail(m, "the forecast service does not know this region")));

    private final ForecastServicePort service;
    private final LayerCache cache;
    private final ForecastProperties properties;
    private final Clock clock;
    private volatile CachedStatus status;

    ForecastService(ForecastServicePort service, LayerCache cache, ForecastProperties properties, Optional<Clock> clock) {
        this.service = service;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** One {@code /healthz} result (null = the service did not answer it) and when to ask again. */
    private record CachedStatus(Status status, long expiresNanos) {
    }

    @Override
    public byte[] forecast(ForecastQuery query) {
        Optional<Status> current = status();
        boolean daily = query.period().dependsOnToday();
        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        LocalDate before = LocalDate.ofInstant(clock.instant().minus(properties.daySkew()), KST);
        String json = query.json();
        String version = current.map(s -> s.dataVersion() + "@" + s.asOf()).orElse(null);
        String key = (daily ? "kst" + today + ":" : "") + sha256(json);
        if (version != null) {
            Optional<String> hit = cache.findText(ROUTE, version, key);
            if (hit.isPresent()) {
                return hit.get().getBytes(StandardCharsets.UTF_8);
            }
        }
        Reply reply = service.forecast(json);
        if (reply.outcome() != Outcome.ANSWERED) {
            throw REFUSALS.get(reply.outcome()).apply(reply.message());
        }
        LocalDate after = LocalDate.ofInstant(clock.instant().plus(properties.daySkew()), KST);
        boolean sameDay = !daily || (before.equals(today) && after.equals(today));
        boolean sameData = current.isPresent() && current.get().asOf().toString().equals(reply.asOf());
        if (version != null && sameDay && sameData) {
            utf8(reply.body()).ifPresent(text -> cache.putText(ROUTE, version, key, text, properties.cacheTtl()));
        }
        return reply.body();
    }

    /** The service's data_version and as_of, reused for status-ttl; a failed {@code /healthz} is remembered as long. */
    private Optional<Status> status() {
        CachedStatus s = status;
        long now = System.nanoTime();
        if (s == null || now - s.expiresNanos() >= 0) {
            Status fresh;
            try {
                fresh = service.status();
            } catch (RuntimeException e) {
                log.warn("forecast service /healthz unavailable ({}); answers are forwarded without the cache for {}",
                        e.getClass().getSimpleName(), properties.statusTtl());
                fresh = null;
            }
            s = new CachedStatus(fresh, now + properties.statusTtl().toNanos());
            status = s;
        }
        return Optional.ofNullable(s.status());
    }

    /** The body as text when it is valid UTF-8 (then text → bytes gives the same bytes back); else it is not stored. */
    private static Optional<String> utf8(byte[] body) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    static String detail(String message, String fallback) {
        if (message == null || message.isBlank() || message.length() > MAX_DETAIL || UNSAFE.matcher(message).matches()) {
            return fallback;
        }
        return message.strip();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
