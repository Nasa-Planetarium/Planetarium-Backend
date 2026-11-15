# `data_not_loaded`

problem `type`: `https://emberline.example/errors/data_not_loaded` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 50501 `DATA_NOT_LOADED` — HTTP 503

title: en “Model outputs not loaded” · ko “모델 자료가 적재되지 않음”

| why | what | how | opinion |
|---|---|---|---|
| no active release, a file absent from the release, or a failed volume. | release argument resolver, adapters. | 503 with `Retry-After: 60`, ERROR at most once per minute. | 503 not 500 — "not ready" is not "bug"; decided per route. |

## 50502 `LAYER_NOT_LOADED` — HTTP 503

title: en “Layer not loaded” · ko “층 자료가 적재되지 않음”

| why | what | how | opinion |
|---|---|---|---|
| the active release does not carry the file a v1.2 layer reads (not loaded yet, or an older release). | v1.2 layer routes — the layer file itself is absent (MAPPING §8.3); a region the layer does not cover is 200 with `coverage.available=false`, not this error. | 503 with `Retry-After: 60`. | separate from 50501 so the operator sees which layer is missing while the rest of the release serves. |
