package io.emberline.shared.error;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Numbered error catalogue {@code DSSNN} (contract/openapi.yaml {@code x-error-catalog}, ARCHITECTURE §3.9).
 * D = band (1 common, 2 catalog, 3 harmonized, 4 observation, 5 ingest·provenance, 6 external, 7 identity·security,
 * 8 reporting·operations, 9 internal), SS = kind (00 format, 01 range·cost, 02 state·conflict, 03 auth·quota, 04 absent,
 * 05 store·upstream, 09 internal), NN = serial. Numbers are never reused; the string {@code code} is shared by several
 * numbers (clients branch on the string, operators locate by the number).
 * Every constant carries the four cells why · what · how · opinion; the same text is the card in {@code docs/errors}.
 */
public enum ErrorCode {

    /**
     * why: bad input or a P4 injection attempt.
     * what: converters, Bean Validation, exactly-one rules, impossible dates, start after end.
     * how: 400 with {@code param}; not retryable; INFO log without stack.
     * opinion: 400 rather than 422 — the existing contract and the front end branch on 400.
     */
    INVALID_PARAM(10001, "invalid_param", 400, null),
    /**
     * why: a wrong client or probing.
     * what: MVC built-in HttpRequestMethodNotSupportedException.
     * how: 405 with an {@code Allow} header.
     * opinion: built-in MVC errors use the same problem shape (ResponseEntityExceptionHandler).
     */
    METHOD_NOT_ALLOWED(10002, "method_not_allowed", 405, null),
    /**
     * why: an {@code Accept} header we cannot serve.
     * what: content negotiation.
     * how: 406, the supported list in {@code detail}.
     * opinion: JSON, GeoJSON and CSV only.
     */
    NOT_ACCEPTABLE(10003, "not_acceptable", 406, null),
    /**
     * why: P2 huge URL.
     * what: container URL limit (8 KB).
     * how: 400, the limit in {@code detail}.
     * opinion: publishing the limit costs nothing.
     */
    QUERY_TOO_LONG(10101, "range_too_large", 400, null),
    /**
     * why: P2 huge body.
     * what: request body over 256 KB.
     * how: 413, the limit in {@code detail}.
     * opinion: 413 by HTTP meaning; clients branch on {@code code}, so the status does not break them.
     */
    BODY_TOO_LARGE(10102, "range_too_large", 413, null),
    /**
     * why: P1 scraper or over-eager refresh.
     * what: RateLimitFilter.
     * how: 429 with an integer {@code Retry-After}, written by ProblemWriter, counted per minute.
     * opinion: no event per request (log flood).
     */
    RATE_LIMITED(10301, "rate_limited", 429, null),
    /**
     * why: a typo or probing.
     * what: no handler for the path.
     * how: 404, nothing else.
     * opinion: says only that the route does not exist, no internals.
     */
    ROUTE_NOT_FOUND(10401, "not_found", 404, null),
    /**
     * why: a stale link or key explosion (P6).
     * what: CatalogApi.requireAoi against the in-memory catalog of the pinned release.
     * how: 404 with {@code param}, thrown before any cache key is built.
     * opinion: no negative cache needed; the prototype's unknown_country/unknown_region are not carried over.
     */
    UNKNOWN_AOI(20401, "unknown_aoi", 404, null),
    /**
     * why: a calendar range over 40 years.
     * what: request record constraint.
     * how: 400 with {@code param=end_year}.
     * opinion: —
     */
    CALENDAR_RANGE_TOO_LARGE(30101, "range_too_large", 400, null),
    /**
     * why: a synchronous CSV over 100,000 rows.
     * what: the service estimates the row count first.
     * how: 400, {@code detail} says how to narrow the range.
     * opinion: refused until the asynchronous export path exists.
     */
    CSV_ROWS_TOO_MANY(30102, "range_too_large", 400, null),
    /**
     * why: self-intersection, NaN, or coordinates beyond ±180 / ±90.
     * what: GeoPolygon constructor.
     * how: 400 with {@code param=polygon}.
     * opinion: —
     */
    INVALID_GEOMETRY(40001, "invalid_param", 400, null),
    /**
     * why: P1·P2 cost attack.
     * what: query cost rule (period, area × days, limit).
     * how: 400 with {@code param}, hint to /api/v1/cells.
     * opinion: the wider the query, the more it should use pre-aggregates.
     */
    OBSERVATION_RANGE_TOO_LARGE(40101, "range_too_large", 400, null),
    /**
     * why: P2 polygon bomb.
     * what: polygon converter (vertices over 5,000 or rings over 50), counted while parsing.
     * how: 400 with {@code param=polygon}.
     * opinion: same code as prototype v3; count before JTS isValid.
     */
    POLYGON_TOO_COMPLEX(40102, "polygon_too_complex", 400, null),
    /**
     * why: a cell request over a large area (P6 variant).
     * what: /cells with more than 10,000 cells or level world·continent.
     * how: 400, hint to the aoi summary.
     * opinion: blocks multi-MB responses and cache pressure.
     */
    CELLS_TOO_MANY(40103, "range_too_large", 400, null),
    /**
     * why: paging across a release switch.
     * what: the cursor's data version differs from the pinned release.
     * how: 409, start again from the first page.
     * opinion: same as the prototype.
     */
    CURSOR_STALE(40201, "cursor_stale", 409, null),
    /**
     * why: operator mistake or a broken release folder.
     * what: release validator (files, columns, code values, duplicate keys, per-file sha256, method_version).
     * how: CLI exit 3 with reason_codes; the release becomes REJECTED.
     * opinion: the active release stays as it is.
     */
    RELEASE_INVALID(50001, "invalid_param", null, 3),
    /**
     * why: P8 tampered release.
     * what: sha256 of the release manifest differs from the value given on a separate channel.
     * how: CLI exit 4, the release becomes REJECTED, alert.
     * opinion: a hash inside the same folder cannot prove where the folder came from.
     */
    RELEASE_HASH_MISMATCH(50002, "release_hash_mismatch", null, 4),
    /**
     * why: path escape attempt.
     * what: release key regex {@code ^[a-z0-9][a-z0-9._-]{0,63}$} plus normalize().startsWith(root).
     * how: CLI exit 3, no directory is created.
     * opinion: checked before any file system access.
     */
    RELEASE_KEY_INVALID(50003, "invalid_param", null, 3),
    /**
     * why: re-staging while loading, or activating a release that was not loaded.
     * what: Release aggregate state transitions.
     * how: CLI exit 5 / HTTP 409, audit log.
     * opinion: the aggregate enforces transitions, not the caller.
     */
    RELEASE_STATE_CONFLICT(50201, "conflict", 409, 5),
    /**
     * why: no active release, a file absent from the release, or a failed volume.
     * what: release argument resolver, adapters.
     * how: 503 with {@code Retry-After: 60}, ERROR at most once per minute.
     * opinion: 503 not 500 — "not ready" is not "bug"; decided per route.
     */
    DATA_NOT_LOADED(50501, "data_not_loaded", 503, null),
    /**
     * why: FIRMS answered 4xx/5xx.
     * what: FirmsWmsClient.
     * how: 502, upstream body discarded, log has status and key-masked URL.
     * opinion: an upstream body may echo the key.
     */
    UPSTREAM_ERROR(60501, "upstream_error", 502, null),
    /**
     * why: no key, 5 s timeout, global budget exhausted.
     * what: FirmsKeyProvider, client, budget bucket.
     * how: 503 with {@code Retry-After}; other routes stay healthy.
     * opinion: a missing key is not a startup failure, only this route answers 503.
     */
    UPSTREAM_UNAVAILABLE(60502, "upstream_unavailable", 503, null),
    /**
     * why: no or expired session.
     * what: AuthenticationEntryPoint.
     * how: 401 through ProblemWriter.
     * opinion: B5.
     */
    UNAUTHORIZED(70301, "unauthorized", 401, null),
    /**
     * why: P5 privilege escalation.
     * what: AccessDeniedHandler.
     * how: 403, recorded by SecurityEventRecorder (REQUIRES_NEW).
     * opinion: only same-agency, insufficient-role cases are 403.
     */
    FORBIDDEN(70302, "forbidden", 403, null),
    /**
     * why: P5 cross-site request forgery.
     * what: CSRF filter.
     * how: 403, recorded.
     * opinion: B5.
     */
    CSRF_REJECTED(70303, "csrf_rejected", 403, null),
    /**
     * why: wrong password or unknown account.
     * what: LoginService.
     * how: 401 with a byte-identical body; unknown accounts also run a dummy Argon2id check.
     * opinion: exponential delay instead of account lock (P9).
     */
    LOGIN_FAILED(70304, "login_failed", 401, null),
    /**
     * why: absent report or one owned by another agency (IDOR).
     * what: repository condition agency_id = :principalAgency.
     * how: 404.
     * opinion: hides existence (404 instead of 403).
     */
    NOT_FOUND(80401, "not_found", 404, null),
    /**
     * why: the same Idempotency-Key with a different body.
     * what: unique constraint.
     * how: 409; the same key with the same body replays the original response.
     * opinion: B5.
     */
    IDEMPOTENCY_CONFLICT(80201, "conflict", 409, null),
    /**
     * why: If-Match does not match.
     * what: version column.
     * how: 412 with the current ETag.
     * opinion: B5.
     */
    PRECONDITION_FAILED(80202, "conflict", 412, null),
    /**
     * why: our bug.
     * what: the last handler of the advice.
     * how: 500, body has the request_id only, ERROR with stack in the log, alert.
     * opinion: RFC 9457 — never expose implementation details.
     */
    INTERNAL(90901, "internal", 500, null),
    /**
     * why: P2 burst or a legitimate surge.
     * what: bulkhead (4 heavy requests per pod), 10 s request limit, statement_timeout 5 s.
     * how: 503 with {@code Retry-After}.
     * opinion: reject at once instead of queueing — the host has run out of memory before.
     */
    OVERLOADED(90902, "overloaded", 503, null);

    private static final Map<Integer, ErrorCode> BY_NUMBER =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ErrorCode::number, Function.identity()));

    private final int number;
    private final String code;
    private final Integer httpStatus;
    private final Integer cliExit;

    ErrorCode(int number, String code, Integer httpStatus, Integer cliExit) {
        this.number = number;
        this.code = code;
        this.httpStatus = httpStatus;
        this.cliExit = cliExit;
    }

    public int number() {
        return number;
    }

    /** The string code clients branch on (contract enum {@code ErrorCode}). */
    public String code() {
        return code;
    }

    /** HTTP status; CLI-only codes answer 500 if they ever reach HTTP (they should not). */
    public int httpStatus() {
        return httpStatus == null ? 500 : httpStatus;
    }

    public Optional<Integer> cliExit() {
        return Optional.ofNullable(cliExit);
    }

    /** Message key of the localized title ({@code messages*.properties}). */
    public String titleKey() {
        return "error." + name() + ".title";
    }

    public static Optional<ErrorCode> ofNumber(int number) {
        return Optional.ofNullable(BY_NUMBER.get(number));
    }
}
