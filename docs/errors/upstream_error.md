# `upstream_error`

problem `type`: `https://emberline.example/errors/upstream_error` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 60501 `UPSTREAM_ERROR` — HTTP 502

title: en “Upstream error” · ko “외부 서비스 오류”

| why | what | how | opinion |
|---|---|---|---|
| FIRMS answered 4xx/5xx. | FirmsWmsClient. | 502, upstream body discarded, log has status and key-masked URL. | an upstream body may echo the key. |
