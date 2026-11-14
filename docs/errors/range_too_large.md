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
