package io.emberline.provenance.application.service;

import io.emberline.provenance.application.port.in.GetSatelliteStatusUseCase;
import io.emberline.provenance.application.port.in.SatelliteStatusQuery;
import io.emberline.provenance.application.port.in.SatelliteStatusResponse;
import io.emberline.provenance.application.port.out.ProvenanceReadPort;
import io.emberline.provenance.domain.SatelliteDay;
import io.emberline.provenance.domain.SatelliteMonth;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.release.ReleaseRef;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Satellite status from the v1.2 release files (contract getSatelliteStatus): {@code freq=day} = satellite_day_status
 * rows of start..end (UTC {@code date_utc}), at most {@code emberline.limits.status-day-days} days (400 range_too_large
 * 40107); {@code freq=month} = satellite_month_status rows, no period limit (start/end only narrow the months).
 * {@code satellite} keeps the listed satellites. A release without the file of the asked freq → 503 50502. Answers are
 * cached under data_version + the normalized query.
 */
@Service
class SatelliteStatusService implements GetSatelliteStatusUseCase {

    static final LayerRoute ROUTE = LayerRoute.SATELLITE_STATUS;
    static final String MONTH = "month";
    static final int DEFAULT_DAYS = 30;

    private final ProvenanceReadPort port;
    private final LayerCache cache;
    private final int maxDays;

    SatelliteStatusService(ProvenanceReadPort port, LayerCache cache, EmberlineProperties properties) {
        this.port = port;
        this.cache = cache;
        this.maxDays = properties.limits().statusDayDays();
    }

    @Override
    public SatelliteStatusResponse status(ReleaseRef release, SatelliteStatusQuery query) {
        checkYear(query.start(), "start");
        checkYear(query.end(), "end");
        if (query.start() != null && query.end() != null && query.start().isAfter(query.end())) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "start", "start must not be after end");
        }
        return MONTH.equals(query.freq()) ? months(release, query) : days(release, query);
    }

    private SatelliteStatusResponse days(ReleaseRef release, SatelliteStatusQuery query) {
        if (query.start() != null && query.end() != null) {
            checkDays(query.start(), query.end());
        }
        Optional<LocalDate> through = port.satelliteDayThrough(release.releaseId());
        if (through.isEmpty()) {
            throw new DataUnavailableException(ErrorCode.LAYER_NOT_LOADED, "satellite_day_status is not loaded in this release");
        }
        LocalDate end = query.end() != null ? query.end() : through.get();
        LocalDate start = query.start() != null ? query.start() : end.minusDays(DEFAULT_DAYS - 1);
        if (start.isAfter(end)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "start", "start must not be after end (end defaults to "
                    + through.get() + ", the last date_utc)");
        }
        checkDays(start, end);
        String normalized = "end=" + end + "&freq=day&satellite=" + String.join(",", new TreeSet<>(query.satellites())) + "&start=" + start;
        return cache.get(ROUTE, release, normalized, SatelliteStatusResponse.class, () -> {
            List<SatelliteStatusResponse.Day> days = port.satelliteDays(release.releaseId(), start, end).stream()
                    .filter(d -> query.satellites().isEmpty() || query.satellites().contains(d.satellite()))
                    .map(SatelliteStatusService::day).toList();
            return new SatelliteStatusResponse(start.toString(), end.toString(), "day", through.get().toString(), days,
                    List.of(), release.methodVersion(), release.dataRelease());
        });
    }

    private SatelliteStatusResponse months(ReleaseRef release, SatelliteStatusQuery query) {
        String normalized = "end=" + text(query.end()) + "&freq=month&satellite=" + String.join(",", new TreeSet<>(query.satellites()))
                + "&start=" + text(query.start());
        return cache.get(ROUTE, release, normalized, SatelliteStatusResponse.class, () -> {
            List<SatelliteMonth> rows = port.satelliteMonths(release.releaseId());
            if (rows.isEmpty()) {
                throw new DataUnavailableException(ErrorCode.LAYER_NOT_LOADED, "satellite_month_status is not loaded in this release");
            }
            YearMonth from = query.start() == null ? null : YearMonth.from(query.start());
            YearMonth to = query.end() == null ? null : YearMonth.from(query.end());
            List<SatelliteStatusResponse.Month> months = rows.stream()
                    .filter(m -> query.satellites().isEmpty() || query.satellites().contains(m.satellite()))
                    .filter(m -> from == null || !YearMonth.of(m.year(), m.month()).isBefore(from))
                    .filter(m -> to == null || !YearMonth.of(m.year(), m.month()).isAfter(to))
                    .map(SatelliteStatusService::month).toList();
            String through = port.satelliteDayThrough(release.releaseId()).map(LocalDate::toString).orElse(null);
            return new SatelliteStatusResponse(text(query.start()), text(query.end()), MONTH, through, List.of(), months,
                    release.methodVersion(), release.dataRelease());
        });
    }

    private void checkDays(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > maxDays) {
            throw new InvalidRequestException(ErrorCode.SERIES_RANGE_TOO_LARGE, "end", "freq=day covers at most " + maxDays
                    + " days (start..end has " + days + "); use freq=month or a shorter period");
        }
    }

    private static void checkYear(LocalDate d, String param) {
        if (d != null && (d.getYear() < 2000 || d.getYear() > 2100)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " must be within 2000-01-01..2100-12-31");
        }
    }

    private static String text(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static SatelliteStatusResponse.Day day(SatelliteDay d) {
        return new SatelliteStatusResponse.Day(d.satellite(), d.date().toString(), d.status(), d.nDetGlobal(), d.coverage(),
                d.ratioToSeasonal(), d.note());
    }

    private static SatelliteStatusResponse.Month month(SatelliteMonth m) {
        return new SatelliteStatusResponse.Month(m.satellite(), m.year(), m.month(), m.obsDays(), m.lowDays(), m.missingDays(),
                m.daysInMonth(), text(m.firstDay()), text(m.lastDay()), m.monthStatus());
    }
}
