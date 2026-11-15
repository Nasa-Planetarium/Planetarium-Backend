# `unknown_aoi`

problem `type`: `https://emberline.example/errors/unknown_aoi` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 20401 `UNKNOWN_AOI` — HTTP 404

title: en “Unknown area” · ko “알 수 없는 지역”

| why | what | how | opinion |
|---|---|---|---|
| a stale link or key explosion (P6). | CatalogApi.requireAoi against the in-memory catalog of the pinned release. | 404 with `param`, thrown before any cache key is built. | no negative cache needed; the prototype's unknown_country/unknown_region are not carried over. |

## 60401 `FORECAST_UNKNOWN_REGION` — HTTP 404

title: en “Unknown region” · ko “알 수 없는 지역”

| why | what | how | opinion |
|---|---|---|---|
| an aoi_id with the right format that aoi_list does not have. | forecast service 404 with its JSON error body (MAPPING §9.4). | 404 with `param=region`. | same `unknown_aoi` string as 20401; the number tells the operator the forecast service said it. |
