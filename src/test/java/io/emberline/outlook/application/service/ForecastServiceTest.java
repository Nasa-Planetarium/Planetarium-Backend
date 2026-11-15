package io.emberline.outlook.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emberline.outlook.application.port.in.ForecastQuery;
import io.emberline.outlook.application.port.out.ForecastServicePort;
import io.emberline.outlook.domain.ForecastPeriod;
import io.emberline.outlook.domain.ForecastRegion;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.error.EmberlineException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * cache rules of {@link ForecastService} on a settable clock: a {@code horizon_days} answer is keyed by the KST
 * date (a 30-day query is never served across days) and is not stored when the KST date changes within
 * {@code day-skew} of the call; a start…end answer is not keyed by date; an answer from another as_of than
 * {@code /healthz} is not stored. The redis side is the shared layer cache (ForecastApiTest).
 */
class ForecastServiceTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static final class SettableClock extends Clock {
        Instant now;

        SettableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** In-memory text cache with the same find/put contract as the shared layer cache. */
    static final class MemoryCache implements LayerCache {
        final Map<String, String> entries = new ConcurrentHashMap<>();

        @Override
        public <T> T get(LayerRoute route, ReleaseRef release, String normalizedQuery, Class<T> type, Supplier<T> compute) {
            return compute.get();
        }

        @Override
        public String json(LayerRoute route, ReleaseRef release, String normalizedQuery, Supplier<String> serialize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> findText(LayerRoute route, String version, String normalizedQuery) {
            return Optional.ofNullable(entries.get(route.code() + "|" + version + "|" + normalizedQuery));
        }

        @Override
        public void putText(LayerRoute route, String version, String normalizedQuery, String text, Duration ttl) {
            entries.put(route.code() + "|" + version + "|" + normalizedQuery, text);
        }

        @Override
        public String key(LayerRoute route, ReleaseRef release, String normalizedQuery) {
            return route.code() + "|" + release.dataVersion() + "|" + normalizedQuery;
        }
    }

    static final class FakeService implements ForecastServicePort {
        final List<String> calls = new ArrayList<>();
        String asOf = "2026-11-14";
        String answerAsOf = "2026-11-14";
        Reply refusal;
        /** Moves the clock while the service "computes" (a call across midnight). */
        Runnable during = () -> { };

        @Override
        public Status status() {
            return new Status("planner#1", LocalDate.parse(asOf), true);
        }

        @Override
        public Reply forecast(String queryJson) {
            calls.add(queryJson);
            during.run();
            if (refusal != null) {
                return refusal;
            }
            return Reply.answered(("{\"as_of\": \"" + answerAsOf + "\", \"n\": " + calls.size() + "}").getBytes(StandardCharsets.UTF_8), answerAsOf);
        }
    }

    final SettableClock clock = new SettableClock(at("2026-11-15T12:00:00"));
    final FakeService port = new FakeService();
    final MemoryCache cache = new MemoryCache();
    final ForecastService service = new ForecastService(port, cache,
            new ForecastProperties(null, null, null, null, Duration.ofNanos(1), null, Duration.ofMinutes(2)), Optional.of(clock));

    static Instant at(String kstLocal) {
        return java.time.LocalDateTime.parse(kstLocal).atZone(KST).toInstant();
    }

    static ForecastQuery horizon30() {
        return new ForecastQuery(new ForecastRegion.Aoi("SIDO_51"), ForecastPeriod.Horizon.of(30, null, 30));
    }

    static ForecastQuery december() {
        return new ForecastQuery(new ForecastRegion.Aoi("SIDO_51"),
                ForecastPeriod.Range.of(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"), 400));
    }

    @Test
    void aHorizonQueryIsAHitTheSameKstDayAndAMissTheNextDay() {
        byte[] first = service.forecast(horizon30());
        clock.now = at("2026-11-15T23:50:00");
        assertThat(service.forecast(horizon30())).isEqualTo(first);
        assertThat(port.calls).hasSize(1);
        // 00:10 KST on the 16th (15:10 UTC on the 15th — the UTC date has not changed, the KST date has)
        clock.now = at("2026-11-16T00:10:00");
        assertThat(LocalDate.ofInstant(clock.now, ZoneOffset.UTC)).isEqualTo(LocalDate.parse("2026-11-15"));
        byte[] next = service.forecast(horizon30());
        assertThat(next).isNotEqualTo(first);
        assertThat(port.calls).hasSize(2);
        assertThat(cache.entries.keySet()).anyMatch(k -> k.contains("|kst2026-11-15:")).anyMatch(k -> k.contains("|kst2026-11-16:"));
    }

    @Test
    void aHorizonAnswerNearKstMidnightIsNotStored() {
        clock.now = at("2026-11-15T23:59:00");   // within day-skew (2 min) of midnight
        service.forecast(horizon30());
        service.forecast(horizon30());
        assertThat(port.calls).hasSize(2);
        assertThat(cache.entries).isEmpty();
        // a call that starts on the 15th and ends on the 16th
        clock.now = at("2026-11-15T23:00:00");
        port.during = () -> clock.now = at("2026-11-16T00:00:30");
        service.forecast(horizon30());
        assertThat(cache.entries).isEmpty();
    }

    @Test
    void aRangeQueryIsNotKeyedByDate() {
        byte[] first = service.forecast(december());
        clock.now = at("2026-11-16T09:00:00");
        assertThat(service.forecast(december())).isEqualTo(first);
        assertThat(port.calls).hasSize(1);
        assertThat(cache.entries.keySet()).allMatch(k -> k.matches("forecast\\|planner#1@2026-11-14\\|[0-9a-f]{64}"));
        // near midnight does not matter for a range
        clock.now = at("2026-11-16T23:59:30");
        service.forecast(new ForecastQuery(new ForecastRegion.Aoi("SIDO_11"),
                ForecastPeriod.Range.of(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"), 400)));
        assertThat(cache.entries).hasSize(2);
    }

    @Test
    void anAsOfChangeSwitchesTheKeyAndAMismatchedAnswerIsNotStored() {
        service.forecast(december());
        port.asOf = "2026-11-15";
        port.answerAsOf = "2026-11-15";
        service.forecast(december());
        assertThat(port.calls).hasSize(2);
        assertThat(cache.entries.keySet()).anyMatch(k -> k.contains("@2026-11-15|"));
        port.answerAsOf = "2026-11-16";     // the service already swapped its data
        service.forecast(new ForecastQuery(new ForecastRegion.Aoi("SIDO_26"), december().period()));
        assertThat(cache.entries).hasSize(2);
    }

    @Test
    void refusalsAreMappedAndNeverStored() {
        port.refusal = ForecastServicePort.Reply.refused(ForecastServicePort.Outcome.UNKNOWN_REGION, "모르는 aoi_id: SGG_1");
        assertThatThrownBy(() -> service.forecast(december())).isInstanceOfSatisfying(EmberlineException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.FORECAST_UNKNOWN_REGION);
            assertThat(e.param()).isEqualTo("region");
            assertThat(e.detail()).isEqualTo("모르는 aoi_id: SGG_1");
        });
        port.refusal = ForecastServicePort.Reply.refused(ForecastServicePort.Outcome.REJECTED, null);
        assertThatThrownBy(() -> service.forecast(december())).isInstanceOfSatisfying(EmberlineException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORECAST_REJECTED));
        assertThat(cache.entries).isEmpty();
    }

    @Test
    void serviceMessagesReachTheClientOnlyWhenSafe() {
        assertThat(ForecastService.detail("2027-01은 기준 달(2026-11)에서 12개월을 넘습니다", "x")).isEqualTo("2027-01은 기준 달(2026-11)에서 12개월을 넘습니다");
        assertThat(ForecastService.detail("see /opt/app/forecast.py", "x")).isEqualTo("x");
        assertThat(ForecastService.detail("planetarium.forecast.region_weights failed", "x")).isEqualTo("x");
        assertThat(ForecastService.detail("two\nlines", "x")).isEqualTo("x");
        assertThat(ForecastService.detail("a".repeat(301), "x")).isEqualTo("x");
        assertThat(ForecastService.detail("  ", "x")).isEqualTo("x");
        assertThat(ForecastService.detail(null, "x")).isEqualTo("x");
    }

    @Test
    void invalidUtf8AnswersArePassedOnButNotStored() {
        ForecastServicePort bytes = new ForecastServicePort() {
            @Override
            public Status status() {
                return new Status("planner#1", LocalDate.parse("2026-11-14"), true);
            }

            @Override
            public Reply forecast(String queryJson) {
                return Reply.answered(new byte[] {'{', '"', (byte) 0xC3, '"', ':', '1', '}'}, "2026-11-14");
            }
        };
        ForecastService s = new ForecastService(bytes, cache, new ForecastProperties(null, null, null, null, null, null, null), Optional.of(clock));
        assertThat(s.forecast(december())).containsExactly('{', '"', (byte) 0xC3, '"', ':', '1', '}');
        assertThat(cache.entries).isEmpty();
    }
}
