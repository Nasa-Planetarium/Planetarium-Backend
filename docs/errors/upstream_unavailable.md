# `upstream_unavailable`

problem `type`: `https://emberline.example/errors/upstream_unavailable` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 60502 `UPSTREAM_UNAVAILABLE` — HTTP 503

title: en “Upstream unavailable” · ko “외부 서비스를 쓸 수 없음”

| why | what | how | opinion |
|---|---|---|---|
| no key, 5 s timeout, global budget exhausted. | FirmsKeyProvider, client, budget bucket. | 503 with `Retry-After`; other routes stay healthy. | a missing key is not a startup failure, only this route answers 503. |

## 60503 `FORECAST_UNAVAILABLE` — HTTP 503

title: en “Forecast service unavailable” · ko “전망 서비스를 쓸 수 없음”

| why | what | how | opinion |
|---|---|---|---|
| the forecast service is down, slow, restarting or answering something we do not understand. | ForecastHttpClient — connect error, timeout, 5xx, a 4xx that is not the service's JSON error, a 200 that is not a JSON object or is larger than emberline.forecast.max-response-bytes. | 503 with `Retry-After`; the service body is never echoed; WARN log with the status only. | other routes stay healthy; the forecast screen shows "try again" rather than a wrong number. |
