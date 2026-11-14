# `conflict`

problem `type`: `https://emberline.example/errors/conflict` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 50201 `RELEASE_STATE_CONFLICT` — HTTP 409 · CLI exit 5

title: en “Release state conflict” · ko “release 상태 충돌”

| why | what | how | opinion |
|---|---|---|---|
| re-staging while loading, or activating a release that was not loaded. | Release aggregate state transitions. | CLI exit 5 / HTTP 409, audit log. | the aggregate enforces transitions, not the caller. |

## 80201 `IDEMPOTENCY_CONFLICT` — HTTP 409

title: en “Idempotency conflict” · ko “멱등 키 충돌”

| why | what | how | opinion |
|---|---|---|---|
| the same Idempotency-Key with a different body. | unique constraint. | 409; the same key with the same body replays the original response. | B5. |

## 80202 `PRECONDITION_FAILED` — HTTP 412

title: en “Precondition failed” · ko “전제 조건 불일치”

| why | what | how | opinion |
|---|---|---|---|
| If-Match does not match. | version column. | 412 with the current ETag. | B5. |
