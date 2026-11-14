# `overloaded`

problem `type`: `https://emberline.example/errors/overloaded` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 90902 `OVERLOADED` — HTTP 503

title: en “Server overloaded” · ko “서버 과부하”

| why | what | how | opinion |
|---|---|---|---|
| P2 burst or a legitimate surge. | bulkhead (4 heavy requests per pod), 10 s request limit, statement_timeout 5 s. | 503 with `Retry-After`. | reject at once instead of queueing — the host has run out of memory before. |
