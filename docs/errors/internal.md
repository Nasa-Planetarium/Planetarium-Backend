# `internal`

problem `type`: `https://emberline.example/errors/internal` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 90901 `INTERNAL` — HTTP 500

title: en “Internal error” · ko “내부 오류”

| why | what | how | opinion |
|---|---|---|---|
| our bug. | the last handler of the advice. | 500, body has the request_id only, ERROR with stack in the log, alert. | RFC 9457 — never expose implementation details. |
