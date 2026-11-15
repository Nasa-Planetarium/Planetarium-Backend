package io.emberline.outlook.domain;

import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * The period of {@code POST /forecast}, in the form the client sent it (model contract: {@code start}·{@code end}, or
 * {@code horizon_days} with an optional {@code start}). It is forwarded in that form: the model resolves
 * {@code horizon_days} against its own "today" (KST) and as_of, and that rule is the model's (forecast.py), not ours.
 */
public sealed interface ForecastPeriod {

    /** Member name → canonical JSON value, as the forecast body and the cache key carry them. */
    Map<String, String> members();

    /** True when the answer depends on the KST date of the request (horizon_days): the cache key then carries it. */
    boolean dependsOnToday();

    /** {@code horizon_days} (1 … max) days from {@code start}, or from today (KST) when {@code start} is null. */
    record Horizon(int days, LocalDate start) implements ForecastPeriod {

        public static Horizon of(long days, LocalDate start, int maxDays) {
            if (days < 1 || days > maxDays) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_PERIOD, "horizon_days",
                        "horizon_days must be an integer from 1 to " + maxDays);
            }
            return new Horizon((int) days, start);
        }

        @Override
        public Map<String, String> members() {
            return start == null ? Map.of("horizon_days", Integer.toString(days))
                    : Map.of("horizon_days", Integer.toString(days), "start", "\"" + start + "\"");
        }

        @Override
        public boolean dependsOnToday() {
            return true;
        }
    }

    /** {@code start} … {@code end}, both days included, at most {@code maxSpanDays} days. */
    record Range(LocalDate start, LocalDate end) implements ForecastPeriod {

        public static Range of(LocalDate start, LocalDate end, int maxSpanDays) {
            if (end.isBefore(start)) {
                throw new InvalidRequestException(ErrorCode.FORECAST_INVALID_PERIOD, "end", "end must not be before start");
            }
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (days > maxSpanDays) {
                throw new InvalidRequestException(ErrorCode.FORECAST_RANGE_TOO_LARGE, "end",
                        "start..end covers " + days + " days; at most " + maxSpanDays + " are allowed");
            }
            return new Range(start, end);
        }

        @Override
        public Map<String, String> members() {
            return Map.of("start", "\"" + start + "\"", "end", "\"" + end + "\"");
        }

        @Override
        public boolean dependsOnToday() {
            return false;
        }
    }
}
