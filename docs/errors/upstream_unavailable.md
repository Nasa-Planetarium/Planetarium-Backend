# `upstream_unavailable`

problem `type`: `https://emberline.example/errors/upstream_unavailable` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 60502 `UPSTREAM_UNAVAILABLE` — HTTP 503

title: en “Upstream unavailable” · ko “외부 서비스를 쓸 수 없음”

| why | what | how | opinion |
|---|---|---|---|
| no key, 5 s timeout, global budget exhausted. | FirmsKeyProvider, client, budget bucket. | 503 with `Retry-After`; other routes stay healthy. | a missing key is not a startup failure, only this route answers 503. |
