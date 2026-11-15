# `invalid_param`

problem `type`: `https://emberline.example/errors/invalid_param` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 10001 `INVALID_PARAM` — HTTP 400

title: en “Invalid parameter” · ko “잘못된 매개변수”

| why | what | how | opinion |
|---|---|---|---|
| bad input or a P4 injection attempt. | converters, Bean Validation, exactly-one rules, impossible dates, start after end. | 400 with `param`; not retryable; INFO log without stack. | 400 rather than 422 — the existing contract and the front end branch on 400. |

## 40001 `INVALID_GEOMETRY` — HTTP 400

title: en “Invalid geometry” · ko “잘못된 도형”

| why | what | how | opinion |
|---|---|---|---|
| self-intersection, NaN, or coordinates beyond ±180 / ±90. | GeoPolygon constructor; the GeoJSON region of `POST /forecast` (ring not closed or under 4 positions, a position that is not 2 finite numbers, JTS invalid — MAPPING §9.2). | 400 with `param=polygon` (`param=region` on /forecast). | the forecast service would fail inside shapely on such a shape; refusing it here keeps a client error 400. |

## 40002 `INVALID_EVENT_ID` — HTTP 400

title: en “Invalid event id” · ko “잘못된 사건 id”

| why | what | how | opinion |
|---|---|---|---|
| a typo, a truncated link or probing. | `/fire-events/{eventId}` against the contract FireEventId pattern `^(KR\|ARG)_\d{8}_\d{4,6}$`. | 400 with `param=event_id`, thrown before any lookup or cache key. | a malformed id is an input error; a well-formed id the release lacks is 40402. |

## 40003 `AOI_AND_BBOX` — HTTP 400

title: en “Conflicting area parameters” · ko “지역 매개변수가 겹침”

| why | what | how | opinion |
|---|---|---|---|
| a client that sends two area selectors and expects one to win. | v1.2 layer routes take exactly one of `aoi_id` and `bbox`; the hand-off aliases accept both names `aoi` and `aoi_id` and refuse them together (MAPPING §8.1). | 400 with `param`. | no silent precedence between the two — the answer would depend on which one the server read first. |

## 40004 `DATE_AND_RANGE` — HTTP 400

title: en “Both date and range given” · ko “date 와 기간을 함께 보냄”

| why | what | how | opinion |
|---|---|---|---|
| one day and a series asked in the same request. | `/aois/{aoiId}/fwi` with `date` together with `start`·`end`. | 400 with `param=date`. | `date` after `as_of_date` is not this error — it answers 200 with `day: null`. |

## 50001 `RELEASE_INVALID` — no HTTP · CLI exit 3

title: en “Release rejected” · ko “release 검사 실패”

| why | what | how | opinion |
|---|---|---|---|
| operator mistake or a broken release folder. | release validator (files, columns, code values, duplicate keys, per-file sha256, method_version). | CLI exit 3 with reason_codes; the release becomes REJECTED. | the active release stays as it is. |

## 50003 `RELEASE_KEY_INVALID` — no HTTP · CLI exit 3

title: en “Invalid release key” · ko “잘못된 release 키”

| why | what | how | opinion |
|---|---|---|---|
| path escape attempt. | release key regex `^[a-z0-9][a-z0-9._-]{0,63`$} plus normalize().startsWith(root). | CLI exit 3, no directory is created. | checked before any file system access. |

## 40005 `FORECAST_INVALID_REGION` — HTTP 400

title: en “Invalid forecast region” · ko “잘못된 전망 지역”

| why | what | how | opinion |
|---|---|---|---|
| a forecast region the planner cannot read (a typo, a swapped bbox, a wrong JSON type). | `POST /forecast` `region` — not an aoi_id string, a bbox array or a GeoJSON object; aoi_id outside the aoi_id pattern; bbox not 4 finite numbers with west < east, south < north inside ±180 / ±90 (MAPPING §9.2). | 400 with `param=region`, before the forecast service is called. | format only — whether a well-formed aoi_id exists is the service's answer (60401). |

## 40006 `FORECAST_INVALID_PERIOD` — HTTP 400

title: en “Invalid forecast period” · ko “잘못된 전망 기간”

| why | what | how | opinion |
|---|---|---|---|
| a forecast period the planner cannot read. | `POST /forecast` period — not exactly one of `horizon_days` (optionally with `start`) and `start`+`end`; `horizon_days` not a JSON integer in 1…`Version.limits` forecast_horizon_days_max; a date that is not YYYY-MM-DD; end before start (MAPPING §9.2). | 400 with `param`, before the forecast service is called. | whether the period falls inside the model's window (after as_of, within 12 months) is the service's answer (60001) — Spring does not repeat the model's calendar. |

## 60001 `FORECAST_REJECTED` — HTTP 400

title: en “Forecast query refused” · ko “전망 질의를 받을 수 없음”

| why | what | how | opinion |
|---|---|---|---|
| the forecast service refused a well-formed query (period before or at as_of, a month beyond the model's 12-month window, a region the model cannot weigh). | forecast service 400 with its JSON error body (MAPPING §9.4). | 400; the service's message is the `detail` when it is safe (no paths, class names, line breaks). | the model owns its calendar and regions; Spring maps, it does not re-check. |
