# `data_not_loaded`

problem `type`: `https://emberline.example/errors/data_not_loaded` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 50501 `DATA_NOT_LOADED` — HTTP 503

title: en “Model outputs not loaded” · ko “모델 자료가 적재되지 않음”

| why | what | how | opinion |
|---|---|---|---|
| no active release, a file absent from the release, or a failed volume. | release argument resolver, adapters. | 503 with `Retry-After: 60`, ERROR at most once per minute. | 503 not 500 — "not ready" is not "bug"; decided per route. |
