# `not_acceptable`

problem `type`: `https://emberline.example/errors/not_acceptable` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 10003 `NOT_ACCEPTABLE` — HTTP 406

title: en “Not acceptable” · ko “지원하지 않는 응답 형식”

| why | what | how | opinion |
|---|---|---|---|
| an `Accept` header we cannot serve. | content negotiation. | 406, the supported list in `detail`. | JSON, GeoJSON and CSV only. |
