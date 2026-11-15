# `range_too_large`

problem `type`: `https://emberline.example/errors/range_too_large` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 10101 `QUERY_TOO_LONG` — HTTP 400

title: en “Query too long” · ko “요청 주소가 너무 김”

| why | what | how | opinion |
|---|---|---|---|
| P2 huge URL. | container URL limit (8 KB). | 400, the limit in `detail`. | publishing the limit costs nothing. |

## 10102 `BODY_TOO_LARGE` — HTTP 413

title: en “Request body too large” · ko “요청 본문이 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| P2 huge body. | request body over 256 KB. | 413, the limit in `detail`. | 413 by HTTP meaning; clients branch on `code`, so the status does not break them. |

## 30101 `CALENDAR_RANGE_TOO_LARGE` — HTTP 400

title: en “Range too large” · ko “범위가 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| a calendar range over 40 years. | request record constraint. | 400 with `param=end_year`. | — |

## 30102 `CSV_ROWS_TOO_MANY` — HTTP 400

title: en “Too many CSV rows” · ko “CSV 행이 너무 많음”

| why | what | how | opinion |
|---|---|---|---|
| a synchronous CSV over 100,000 rows. | the service estimates the row count first. | 400, `detail` says how to narrow the range. | refused until the asynchronous export path exists. |

## 40101 `OBSERVATION_RANGE_TOO_LARGE` — HTTP 400

title: en “Range too large” · ko “범위가 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| P1·P2 cost attack. | query cost rule (period, area × days, limit). | 400 with `param`, hint to /api/v1/cells. | the wider the query, the more it should use pre-aggregates. |

## 40103 `CELLS_TOO_MANY` — HTTP 400

title: en “Too many cells” · ko “칸이 너무 많음”

| why | what | how | opinion |
|---|---|---|---|
| a cell request over a large area (P6 variant). | /cells with more than 10,000 cells or level world·continent. | 400, hint to the aoi summary. | blocks multi-MB responses and cache pressure. |

## 40104 `FRP_RANGE_TOO_LARGE` — HTTP 400

title: en “Range too large” · ko “범위가 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| P1·P2 cost attack on the largest table (97.8 M rows). | `/cells/frp` limits — estimated rows (cells × period) ≤ 50,000, day period ≤ 31 days, month period ≤ 24 months (`Version.limits` frp_rows, frp_day_days, frp_month_months). | 400 with `param`, the limit in `detail`. | estimated before the query; the file needs a (date_local, cell_id) index to answer at all. |

## 40105 `BBOX_AREA_TOO_LARGE` — HTTP 400

title: en “Box too large” · ko “상자가 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| a world-sized box on a layer route (P6 variant). | bbox area over `Version.limits` frp_bbox_area_deg2 (100 deg², `/cells/frp`) or layer_bbox_area_deg2 (other v1.2 layer routes). | 400 with `param=bbox`, the limit in `detail`. | a world box for one day would pass the FRP row limit; the area limit stops it (contract `/cells/frp`). |

## 40106 `EVENTS_RANGE_TOO_LARGE` — HTTP 400

title: en “Range too large” · ko “범위가 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| a fire event list over decades of 1.24 M events. | `/fire-events` period (`start`…`end`) over `Version.limits` fire_events_days. | 400 with `param`, the limit in `detail`. | pages are capped at 1,000 events; the period cap keeps the count and sort cheap. |

## 40107 `SERIES_RANGE_TOO_LARGE` — HTTP 400

title: en “Range too large” · ko “범위가 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| a daily series longer than the screen can use. | daily series of a v1.2 layer over 366 days — `/aois/{aoiId}/fwi` `start`·`end` (`Version.limits` fwi_series_days). | 400 with `param`, the limit in `detail`. | same 366-day span as the daily summary. |

## 40109 `FORECAST_RANGE_TOO_LARGE` — HTTP 400

title: en “Range too large” · ko “범위가 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| a start…end span far beyond anything the planner can answer. | `POST /forecast` `start`…`end` longer than `Version.limits` forecast_span_days. | 400 with `param=end`, the limit in `detail`. | the cap is above the model's own window (≤ 396 days), so it only stops nonsense, never a valid query. |

## 40110 `FORECAST_BODY_TOO_LARGE` — HTTP 413

title: en “Request body too large” · ko “요청 본문이 너무 큼”

| why | what | how | opinion |
|---|---|---|---|
| P2 huge body on the one public POST route. | `POST /forecast` body over `Version.limits` forecast_body_bytes, counted while reading. | 413, the limit in `detail`; the body is never parsed. | same 413 as 10102 by HTTP meaning; its own number because the limit is the route's, not the container's. |
