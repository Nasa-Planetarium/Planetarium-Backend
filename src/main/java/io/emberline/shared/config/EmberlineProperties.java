package io.emberline.shared.config;

import java.nio.file.Path;
import java.util.Optional;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings ({@code emberline.*}). Validated at startup: a bad value stops the process (non-zero exit).
 *
 * @param cors allowed browser origins (EMBERLINE_CORS_ALLOWED_ORIGINS); {@code *} is refused
 * @param limits public limits, the values of contract/openapi.yaml Version.limits
 * @param errors problem {@code type} URI base
 * @param release release root (EMBERLINE_RELEASE_ROOT) and polling of the active release
 * @param ingest ingest tuning (COPY workers, DuckDB memory)
 */
@ConfigurationProperties("emberline")
public record EmberlineProperties(Cors cors, Limits limits, Errors errors, Release release, Ingest ingest) {

    public EmberlineProperties {
        cors = cors == null ? new Cors(List.of()) : cors;
        limits = limits == null ? Limits.CONTRACT : limits;
        errors = errors == null ? new Errors(Errors.DEFAULT_TYPE_BASE) : errors;
        release = release == null ? new Release(null, Duration.ofSeconds(10)) : release;
        ingest = ingest == null ? new Ingest(4, "1GB", 4) : ingest;
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of()
                    : allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (allowedOrigins.stream().anyMatch(o -> o.contains("*"))) {
                throw new IllegalArgumentException("emberline.cors.allowed-origins must list origins; '*' is not allowed");
            }
        }
    }

    /**
     * Field names become the snake_case keys of /api/v1/version {@code limits}. The /hotspots day spans are not here:
     * they live in {@code emberline.hotspots.*} (one source for enforcement and /version). The v1.2 layer limits
     * (fire events … spread nowcast) are only advertised here; the layer work items enforce them from this record.
     * The forecast limits ({@code POST /forecast}) are enforced by the outlook module's ForecastRequestFilter and
     * ForecastBodyReader (docs/DEVIATIONS.md).
     */
    public record Limits(int hotspotsLimitMax, int summaryDayDays, int summaryMonthYears, int calendarYears,
                         int polygonVertices, int cellsMax, int csvRows, int fireEventsLimitMax, int fireEventsDays,
                         double layerBboxAreaDeg2, int frpRows, int frpDayDays, int frpMonthMonths,
                         double frpBboxAreaDeg2, int fwiSeriesDays, int statusDayDays, int spreadCellsMax,
                         int forecastBodyBytes, int forecastPolygonVertices, int forecastHorizonDaysMax,
                         int forecastSpanDays) {

        public static final Limits CONTRACT = new Limits(10000, 366, 40, 40, 5000, 10000, 100000, 1000, 3700, 400, 50000,
                31, 24, 100, 366, 366, 20000, 131072, 2000, 30, 400);

        public Limits {
            int[] all = {hotspotsLimitMax, summaryDayDays, summaryMonthYears, calendarYears, polygonVertices, cellsMax,
                    csvRows, fireEventsLimitMax, fireEventsDays, frpRows, frpDayDays, frpMonthMonths, fwiSeriesDays,
                    statusDayDays, spreadCellsMax, forecastBodyBytes, forecastPolygonVertices, forecastHorizonDaysMax,
                    forecastSpanDays};
            for (int v : all) {
                if (v <= 0) {
                    throw new IllegalArgumentException("emberline.limits.* must be positive");
                }
            }
            if (!(layerBboxAreaDeg2 > 0) || !(frpBboxAreaDeg2 > 0)) {
                throw new IllegalArgumentException("emberline.limits.* must be positive");
            }
        }
    }

    public record Errors(@DefaultValue(Errors.DEFAULT_TYPE_BASE) String typeBase) {
        public static final String DEFAULT_TYPE_BASE = "https://emberline.example/errors";

        public Errors {
            typeBase = typeBase == null || typeBase.isBlank() ? DEFAULT_TYPE_BASE : typeBase.replaceAll("/+$", "");
        }
    }

    public record Release(String root, Duration pollInterval) {

        /** EMBERLINE_RELEASE_ROOT as an absolute, normalized path (empty when not configured). */
        public Optional<Path> rootPath() {
            return root == null || root.isBlank() ? Optional.empty()
                    : Optional.of(Path.of(root).toAbsolutePath().normalize());
        }

        public Release {
            pollInterval = pollInterval == null ? Duration.ofSeconds(10) : pollInterval;
            if (pollInterval.isNegative() || pollInterval.isZero() || pollInterval.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("emberline.release.poll-interval must be within (0, 30s]");
            }
        }
    }

    public record Ingest(int copyWorkers, String duckdbMemoryLimit, int duckdbThreads) {
        public Ingest {
            copyWorkers = Math.max(1, Math.min(copyWorkers, 16));
            duckdbThreads = Math.max(1, Math.min(duckdbThreads, 64));
            duckdbMemoryLimit = duckdbMemoryLimit == null || !duckdbMemoryLimit.matches("^[0-9]+(\\.[0-9]+)?\\s?(MB|GB|MiB|GiB)$")
                    ? "1GB" : duckdbMemoryLimit;
        }
    }
}
