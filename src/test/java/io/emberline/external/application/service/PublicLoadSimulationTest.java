package io.emberline.external.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.external.application.port.in.WmsTileQuery;
import io.emberline.external.application.port.out.TileCachePort;
import io.emberline.external.domain.FirmsKey;
import io.emberline.platform.ratelimit.ClientKeyResolver;
import io.emberline.platform.ratelimit.RateLimitFilter;
import io.emberline.platform.ratelimit.RateLimitProperties;
import io.emberline.platform.ratelimit.TokenBuckets;
import io.emberline.platform.redis.EventsProperties;
import io.emberline.shared.error.UpstreamException;
import io.github.bucket4j.TimeMeter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.yaml.snakeyaml.Yaml;

/**
 * pl-pub item 2 on a simulated clock: the rate-limit scopes ({@link RateLimitFilter#check}) and the FIRMS proxy
 * ({@link WmsTileService} over a fake upstream and an in-memory tile cache) with the configuration the public
 * deployment really runs — application.yml overlaid with the pl-app-env ConfigMap of deploy/k8s/planetarium-app.yaml as
 * environment variables. Requests arrive from pl-web (10.42.0.17, trusted) with X-Forwarded-For = the NAT address.
 *
 * <p>Profile per user: one page view per 10 s = 6 API calls (version, aoi, summary, satellite-status, cells,
 * hotspots; 2 heavy), one map pan per 15 s = 20 tiles out of 30 viewports (600 distinct tiles).
 */
class PublicLoadSimulationTest {

    static final String NAT = "198.51.100.200";
    static final String PL_WEB = "10.42.0.17";
    static final long SECOND = Duration.ofSeconds(1).toNanos();
    static final List<String> PAGE = List.of("/api/v1/version", "/api/v1/aois/KR", "/api/v1/aois/KR/summary",
            "/api/v1/satellite-status", "/api/v1/cells", "/api/v1/hotspots");

    record Config(RateLimitProperties rateLimit, FirmsProperties firms) {
    }

    record Event(long at, String path, WmsTileQuery tile, String client) {
    }

    /** One simulated deployment: buckets on a fake clock, the FIRMS service over a counting fake upstream. */
    static final class Sim {
        final AtomicLong now = new AtomicLong();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Map<String, Integer> statuses = new TreeMap<>();
        final TokenBuckets buckets;
        final RateLimitFilter filter;
        final WmsTileService tiles;

        Sim(Config c) {
            TimeMeter clock = new TimeMeter() {
                @Override
                public long currentTimeNanos() {
                    return now.get();
                }

                @Override
                public boolean isWallClockBased() {
                    return false;
                }
            };
            ClientKeyResolver clients = new ClientKeyResolver(c.rateLimit());
            buckets = new TokenBuckets(null, clients, c.rateLimit(), clock);
            filter = new RateLimitFilter(c.rateLimit(), new EventsProperties(null, "sim", null, null, null, null, null, null, null),
                    clients, buckets, null);
            Map<String, byte[]> cache = new ConcurrentHashMap<>();
            TileCachePort cachePort = new TileCachePort() {
                @Override
                public Optional<byte[]> get(String key) {
                    return Optional.ofNullable(cache.get(key));
                }

                @Override
                public void put(String key, byte[] png, Duration ttl) {
                    cache.put(key, png);
                }
            };
            tiles = new WmsTileService(() -> Optional.of(new FirmsKey("FAKE")), cachePort, (tile, key) -> {
                upstreamCalls.incrementAndGet();
                return new byte[] {(byte) 0x89, 'P', 'N', 'G'};
            }, buckets, c.firms());
        }

        String run(Event e) {
            now.set(e.at());
            MockHttpServletRequest req = new MockHttpServletRequest("GET", e.path());
            req.setRemoteAddr(PL_WEB);
            req.addHeader("X-Forwarded-For", e.client());
            RateLimitFilter.Denial denial = filter.check(req);
            String status = denial != null ? "429 " + denial.scope() : "200";
            if (denial == null && e.tile() != null) {
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
                try {
                    tiles.tile(e.tile());
                } catch (UpstreamException ex) {
                    status = "503 " + ex.detail();
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            }
            statuses.merge(status, 1, Integer::sum);
            return status;
        }
    }

    // ---- configuration ----

    @SuppressWarnings("unchecked")
    static Config config(boolean withConfigMap) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        if (withConfigMap) {
            try (Reader r = Files.newBufferedReader(Path.of("deploy/k8s/planetarium-app.yaml"))) {
                Map<String, Object> cm = (Map<String, Object>) new Yaml().loadAll(r).iterator().next();
                Map<String, Object> env = new HashMap<>();
                ((Map<String, Object>) cm.get("data")).forEach((k, v) -> env.put(k, String.valueOf(v)));
                sources.addFirst(new SystemEnvironmentPropertySource("systemEnvironment", env));
            }
        }
        List<PropertySource<?>> yml = new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        sources.addLast(yml.get(0));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        return new Config(binder.bind("emberline.ratelimit", RateLimitProperties.class).get(),
                binder.bind("emberline.firms", FirmsProperties.class).get());
    }

    static WmsTileQuery tile(double west, double south) {
        return new WmsTileQuery("viirs_noaa20_24h", String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", west, south, west + 0.2, south + 0.2),
                256, 256, null);
    }

    /** 200 users behind {@link #NAT} for {@code seconds}, deterministic offsets. */
    static List<Event> profile(int users, int seconds) {
        Random rnd = new Random(42);
        List<Event> events = new ArrayList<>();
        long end = seconds * SECOND;
        for (int u = 0; u < users; u++) {
            for (long t = (long) (rnd.nextDouble() * 10 * SECOND); t < end; t += 10 * SECOND) {
                for (int i = 0; i < PAGE.size(); i++) {
                    events.add(new Event(t + i * 50_000_000L, PAGE.get(i), null, NAT));
                }
            }
            for (long t = (long) (rnd.nextDouble() * 15 * SECOND); t < end; t += 15 * SECOND) {
                int v = rnd.nextInt(30);
                for (int j = 0; j < 20; j++) {
                    WmsTileQuery q = tile(125 + (v % 6) + (j % 5) * 0.2, 33.2 + (v / 6) + (j / 5) * 0.2);
                    events.add(new Event(t + j * 25_000_000L, "/api/v1/firms/wms-tiles", q, NAT));
                }
            }
        }
        events.sort(Comparator.comparingLong(Event::at));
        return events;
    }

    // ---- rows ----

    @Test
    void publicConfigurationIsTheChosenNumbers() throws IOException {
        Config c = config(true);
        RateLimitProperties rl = c.rateLimit();
        assertThat(rl.ip()).isEqualTo(new RateLimitProperties.Limit(1000L, 500L, Duration.ofSeconds(1)));
        assertThat(rl.heavy()).isEqualTo(new RateLimitProperties.Limit(3600L, 3600L, Duration.ofMinutes(1)));
        assertThat(rl.tiles()).isEqualTo(new RateLimitProperties.Limit(24000L, 24000L, Duration.ofMinutes(1)));
        assertThat(rl.global()).isEqualTo(new RateLimitProperties.Limit(2000L, 700L, Duration.ofSeconds(1)));
        // (docs/DEVIATIONS.md): stricter than heavy, default single-user 10/min
        assertThat(rl.forecast()).isEqualTo(new RateLimitProperties.Limit(1800L, 1800L, Duration.ofMinutes(1)));
        assertThat(rl.forecast().capacity()).isLessThan(rl.heavy().capacity());
        assertThat(config(false).rateLimit().forecast()).isEqualTo(new RateLimitProperties.Limit(10L, 10L, Duration.ofMinutes(1)));
        assertThat(config(false).rateLimit().forecast().capacity()).isLessThan(config(false).rateLimit().heavy().capacity());
        assertThat(rl.trustedProxies()).containsExactly("10.42.0.0/24");
        assertThat(c.firms().budgetCapacity()).isEqualTo(4500L);
        assertThat(c.firms().budgetPeriod()).isEqualTo(Duration.ofMinutes(10));
        assertThat(c.firms().budgetPerCaller()).isEqualTo(1500L);
    }

    @Test
    void twoHundredUsersBehindOneAddressForOneSimulatedMinuteGetNo429() throws IOException {
        List<Event> events = profile(200, 60);
        // 200 × (6 views × 6 calls + 4 pans × 20 tiles) = 23,200
        assertThat(events).hasSize(23_200);
        Sim sim = new Sim(config(true));
        events.forEach(sim::run);
        assertThat(sim.statuses).containsOnlyKeys("200").containsEntry("200", 23_200);
        assertThat(sim.upstreamCalls.get()).isPositive().isLessThanOrEqualTo(600);

        // the same minute against the single-user defaults (application.yml alone) is refused — why the numbers moved
        Sim before = new Sim(config(false));
        events.forEach(before::run);
        assertThat(before.statuses.keySet()).anyMatch(s -> s.startsWith("429"));
    }

    @Test
    void twoHundredUsersWithOneForecastPerPageViewGetNo429AndOneAddressIsCappedAt1800() throws IOException {
        // profile: the same 200 reviewers behind one address, one POST /api/v1/forecast per page view (every 10 s)
        List<Event> events = new ArrayList<>(profile(200, 60));
        Random rnd = new Random(7);
        for (int u = 0; u < 200; u++) {
            for (long t = (long) (rnd.nextDouble() * 10 * SECOND); t < 60 * SECOND; t += 10 * SECOND) {
                events.add(new Event(t + 300_000_000L, "/api/v1/forecast", null, NAT));
            }
        }
        events.sort(Comparator.comparingLong(Event::at));
        assertThat(events).hasSize(23_200 + 1_200);
        Sim sim = new Sim(config(true));
        events.forEach(sim::run);
        assertThat(sim.statuses).containsOnlyKeys("200");
        // a scraper on one address: 1,800 forecasts in a minute, then 429 scope forecast
        Sim scraper = new Sim(config(true));
        for (int i = 0; i < 1_801; i++) {
            scraper.run(new Event(i * 10_000_000L, "/api/v1/forecast", null, "203.0.113.9"));
        }
        assertThat(scraper.statuses).containsEntry("200", 1_800).containsEntry("429 forecast", 1);
    }

    @Test
    void oneAddressCannotExhaustTheFirmsBudget() throws IOException {
        Sim sim = new Sim(config(true));
        // one address asks for 2,000 distinct tiles (all cache misses) within 20 s
        for (int i = 0; i < 2_000; i++) {
            sim.run(new Event(i * 10_000_000L, "/api/v1/firms/wms-tiles", tile(125 + i * 0.0001, 34), "203.0.113.1"));
        }
        assertThat(sim.upstreamCalls.get()).isEqualTo(1_500);
        assertThat(sim.statuses).containsEntry("200", 1_500)
                .containsEntry("503 the FIRMS map layer is busy for this address; try again later", 500);
        // another address still gets an upstream tile
        assertThat(sim.run(new Event(21 * SECOND, "/api/v1/firms/wms-tiles", tile(126.5, 35), "203.0.113.2"))).isEqualTo("200");
        assertThat(sim.upstreamCalls.get()).isEqualTo(1_501);
    }

    @Test
    void theGlobalFirmsBudgetStillHoldsAcrossAddresses() throws IOException {
        Sim sim = new Sim(config(true));
        int n = 0;
        for (int a = 1; a <= 4; a++) {
            for (int i = 0; i < 1_500; i++) {
                sim.run(new Event(n++ * 2_000_000L, "/api/v1/firms/wms-tiles", tile(125 + a + i * 0.0001, 34), "203.0.113." + a));
            }
        }
        assertThat(sim.upstreamCalls.get()).isEqualTo(4_500);
        assertThat(sim.statuses).containsEntry("200", 4_500)
                .containsEntry("503 the FIRMS map layer is busy; try again later", 1_500);
    }
}
