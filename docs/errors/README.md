# Error catalogue

One card per string `code` (several numbers may share a code). Number scheme `DSSNN` — see `ErrorCode` and `contract/openapi.yaml` `x-error-catalog` (48 numbers).

| error_no | constant | code | HTTP / CLI |
|---|---|---|---|
| 10001 | `INVALID_PARAM` | [`invalid_param`](invalid_param.md) | 400 |
| 40001 | `INVALID_GEOMETRY` | [`invalid_param`](invalid_param.md) | 400 |
| 40002 | `INVALID_EVENT_ID` | [`invalid_param`](invalid_param.md) | 400 |
| 40003 | `AOI_AND_BBOX` | [`invalid_param`](invalid_param.md) | 400 |
| 40004 | `DATE_AND_RANGE` | [`invalid_param`](invalid_param.md) | 400 |
| 50001 | `RELEASE_INVALID` | [`invalid_param`](invalid_param.md) | — / exit 3 |
| 50003 | `RELEASE_KEY_INVALID` | [`invalid_param`](invalid_param.md) | — / exit 3 |
| 40005 | `FORECAST_INVALID_REGION` | [`invalid_param`](invalid_param.md) | 400 |
| 40006 | `FORECAST_INVALID_PERIOD` | [`invalid_param`](invalid_param.md) | 400 |
| 60001 | `FORECAST_REJECTED` | [`invalid_param`](invalid_param.md) | 400 |
| 10002 | `METHOD_NOT_ALLOWED` | [`method_not_allowed`](method_not_allowed.md) | 405 |
| 10003 | `NOT_ACCEPTABLE` | [`not_acceptable`](not_acceptable.md) | 406 |
| 10101 | `QUERY_TOO_LONG` | [`range_too_large`](range_too_large.md) | 400 |
| 10102 | `BODY_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 413 |
| 30101 | `CALENDAR_RANGE_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 400 |
| 30102 | `CSV_ROWS_TOO_MANY` | [`range_too_large`](range_too_large.md) | 400 |
| 40101 | `OBSERVATION_RANGE_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 400 |
| 40103 | `CELLS_TOO_MANY` | [`range_too_large`](range_too_large.md) | 400 |
| 40104 | `FRP_RANGE_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 400 |
| 40105 | `BBOX_AREA_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 400 |
| 40106 | `EVENTS_RANGE_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 400 |
| 40107 | `SERIES_RANGE_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 400 |
| 40109 | `FORECAST_RANGE_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 400 |
| 40110 | `FORECAST_BODY_TOO_LARGE` | [`range_too_large`](range_too_large.md) | 413 |
| 10301 | `RATE_LIMITED` | [`rate_limited`](rate_limited.md) | 429 |
| 10401 | `ROUTE_NOT_FOUND` | [`not_found`](not_found.md) | 404 |
| 40402 | `UNKNOWN_FIRE_EVENT` | [`not_found`](not_found.md) | 404 |
| 80401 | `NOT_FOUND` | [`not_found`](not_found.md) | 404 |
| 20401 | `UNKNOWN_AOI` | [`unknown_aoi`](unknown_aoi.md) | 404 |
| 60401 | `FORECAST_UNKNOWN_REGION` | [`unknown_aoi`](unknown_aoi.md) | 404 |
| 40102 | `POLYGON_TOO_COMPLEX` | [`polygon_too_complex`](polygon_too_complex.md) | 400 |
| 40108 | `FORECAST_POLYGON_TOO_COMPLEX` | [`polygon_too_complex`](polygon_too_complex.md) | 400 |
| 40201 | `CURSOR_STALE` | [`cursor_stale`](cursor_stale.md) | 409 |
| 50002 | `RELEASE_HASH_MISMATCH` | [`release_hash_mismatch`](release_hash_mismatch.md) | — / exit 4 |
| 50201 | `RELEASE_STATE_CONFLICT` | [`conflict`](conflict.md) | 409 / exit 5 |
| 80201 | `IDEMPOTENCY_CONFLICT` | [`conflict`](conflict.md) | 409 |
| 80202 | `PRECONDITION_FAILED` | [`conflict`](conflict.md) | 412 |
| 50501 | `DATA_NOT_LOADED` | [`data_not_loaded`](data_not_loaded.md) | 503 |
| 50502 | `LAYER_NOT_LOADED` | [`data_not_loaded`](data_not_loaded.md) | 503 |
| 60501 | `UPSTREAM_ERROR` | [`upstream_error`](upstream_error.md) | 502 |
| 60502 | `UPSTREAM_UNAVAILABLE` | [`upstream_unavailable`](upstream_unavailable.md) | 503 |
| 60503 | `FORECAST_UNAVAILABLE` | [`upstream_unavailable`](upstream_unavailable.md) | 503 |
| 70301 | `UNAUTHORIZED` | [`unauthorized`](unauthorized.md) | 401 |
| 70302 | `FORBIDDEN` | [`forbidden`](forbidden.md) | 403 |
| 70303 | `CSRF_REJECTED` | [`csrf_rejected`](csrf_rejected.md) | 403 |
| 70304 | `LOGIN_FAILED` | [`login_failed`](login_failed.md) | 401 |
| 90901 | `INTERNAL` | [`internal`](internal.md) | 500 |
| 90902 | `OVERLOADED` | [`overloaded`](overloaded.md) | 503 |
