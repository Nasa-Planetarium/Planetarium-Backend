# `not_found`

problem `type`: `https://emberline.example/errors/not_found` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 10401 `ROUTE_NOT_FOUND` — HTTP 404

title: en “Not found” · ko “찾을 수 없음”

| why | what | how | opinion |
|---|---|---|---|
| a typo or probing. | no handler for the path. | 404, nothing else. | says only that the route does not exist, no internals. |

## 80401 `NOT_FOUND` — HTTP 404

title: en “Not found” · ko “찾을 수 없음”

| why | what | how | opinion |
|---|---|---|---|
| absent report or one owned by another agency (IDOR). | repository condition agency_id = :principalAgency. | 404. | hides existence (404 instead of 403). |
