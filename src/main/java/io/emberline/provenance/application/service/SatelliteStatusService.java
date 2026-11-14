package io.emberline.provenance.application.service;

import io.emberline.provenance.application.port.in.GetSatelliteStatusUseCase;
import io.emberline.provenance.application.port.in.SatelliteStatusQuery;
import io.emberline.provenance.application.port.in.SatelliteStatusResponse;
import io.emberline.provenance.application.port.out.ProvenanceReadPort;
import io.emberline.provenance.domain.MonthStatus;
import io.emberline.provenance.domain.SatelliteCoverage;
import io.emberline.provenance.domain.SatelliteDay;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.release.ReleaseRef;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Satellite status from the release. Day level only when the release ships satellite_day_status; otherwise every
 * month in start..end carries the file's month status (value_source / data_source / status) — never invented days.
 */
@Service
class SatelliteStatusService implements GetSatelliteStatusUseCase {

    static final int MAX_YEARS = 40;

    private final ProvenanceReadPort port;

    SatelliteStatusService(ProvenanceReadPort port) {
        this.port = port;
    }

    @Override
    public SatelliteStatusResponse status(ReleaseRef release, SatelliteStatusQuery query) {
        List<MonthStatus> rows = port.monthStatus(release.releaseId());
        if (rows.isEmpty()) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, "the release has no month status");
        }
        Map<Integer, MonthStatus> byMonth = rows.stream().collect(Collectors.toMap(MonthStatus::key, Function.identity(), (a, b) -> a));
        MonthStatus last = rows.stream().max((a, b) -> Integer.compare(a.key(), b.key())).orElseThrow();
        LocalDate end = query.end() != null ? query.end() : YearMonth.of(last.year(), last.month()).atEndOfMonth();
        LocalDate start = query.start() != null ? query.start() : end.minusDays(29);
        check(start, end);

        List<SatelliteStatusResponse.Month> months = new ArrayList<>();
        Map<String, Map<String, Integer>> counts = new TreeMap<>((a, b) -> Integer.compare(
                SatelliteCoverage.ORDER.indexOf(a), SatelliteCoverage.ORDER.indexOf(b)));
        Map<String, LocalDate[]> spans = new LinkedHashMap<>();
        for (YearMonth ym = YearMonth.from(start); !ym.isAfter(YearMonth.from(end)); ym = ym.plusMonths(1)) {
            MonthStatus row = byMonth.get(ym.getYear() * 100 + ym.getMonthValue());
            String status = row == null ? "out_of_range" : row.status();
            List<String> satellites = row == null ? List.of() : SatelliteCoverage.of(row.valueSource());
            months.add(new SatelliteStatusResponse.Month(ym.toString(), ym.getYear(), ym.getMonthValue(),
                    row == null ? null : row.valueSource(), row == null ? null : row.dataSource(), status, satellites));
            LocalDate from = ym.atDay(1).isBefore(start) ? start : ym.atDay(1);
            LocalDate to = ym.atEndOfMonth().isAfter(end) ? end : ym.atEndOfMonth();
            for (String sat : satellites) {
                counts.computeIfAbsent(sat, k -> new TreeMap<>()).merge(status, 1, Integer::sum);
                LocalDate[] span = spans.computeIfAbsent(sat, k -> new LocalDate[] {from, to});
                span[1] = to;
            }
        }
        List<SatelliteStatusResponse.Satellite> satellites = new ArrayList<>();
        counts.forEach((sat, c) -> satellites.add(new SatelliteStatusResponse.Satellite(sat, SatelliteCoverage.INSTRUMENT.get(sat),
                spans.get(sat)[0].toString(), spans.get(sat)[1].toString(), c)));

        boolean dayLevel = port.hasSatelliteDays(release.releaseId());
        List<SatelliteStatusResponse.Day> days = dayLevel ? port.satelliteDays(release.releaseId(), start, end).stream()
                .map(SatelliteStatusService::day).toList() : null;
        return new SatelliteStatusResponse(start.toString(), end.toString(), dayLevel ? "day" : "month",
                dayLevel ? "provided" : "not_provided", release.loadedAt() == null ? null : release.loadedAt().toString(),
                satellites, months, days, release.methodVersion());
    }

    private static SatelliteStatusResponse.Day day(SatelliteDay d) {
        return new SatelliteStatusResponse.Day(d.date().toString(), d.satellite(), d.status(), d.dataSource());
    }

    private static void check(LocalDate start, LocalDate end) {
        if (start.getYear() < 2000 || start.getYear() > 2100) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "start", "start must be within 2000-01-01..2100-12-31");
        }
        if (end.getYear() < 2000 || end.getYear() > 2100) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "end", "end must be within 2000-01-01..2100-12-31");
        }
        if (start.isAfter(end)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "start", "start must not be after end");
        }
        if (ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) >= MAX_YEARS * 12L) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "end", "start..end covers at most " + MAX_YEARS + " years");
        }
    }
}
