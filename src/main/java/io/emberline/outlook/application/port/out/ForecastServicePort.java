package io.emberline.outlook.application.port.out;

import java.time.LocalDate;

/**
 * The model team's forecast service (planetarium.forecast behind HTTP; internal interface in
 * contract/MAPPING.md §9.3). Transport failures — no connection, timeout, 5xx, an answer we cannot read — are thrown
 * as {@code UpstreamException(FORECAST_UNAVAILABLE)}; the service's own refusals come back as a {@link Reply}.
 */
public interface ForecastServicePort {

    /** {@code GET /healthz}: which data the service answers from. */
    Status status();

    /** {@code POST /forecast} with the canonical query JSON. */
    Reply forecast(String queryJson);

    /** The service's data: its data_version, as_of (last complete observation day) and whether warmup() has run. */
    record Status(String dataVersion, LocalDate asOf, boolean warm) {
    }

    /** What the service said: an answer (a JSON object, verbatim), or a refusal with its own message. */
    record Reply(Outcome outcome, byte[] body, String asOf, String message) {

        public static Reply answered(byte[] body, String asOf) {
            return new Reply(Outcome.ANSWERED, body, asOf, null);
        }

        public static Reply refused(Outcome outcome, String message) {
            return new Reply(outcome, null, null, message);
        }
    }

    /** ANSWERED = 200 JSON object; REJECTED = 400 with the JSON error; UNKNOWN_REGION = 404 with the JSON error. */
    enum Outcome { ANSWERED, REJECTED, UNKNOWN_REGION }
}
