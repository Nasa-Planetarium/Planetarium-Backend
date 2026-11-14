# `cursor_stale`

problem `type`: `https://emberline.example/errors/cursor_stale` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 40201 `CURSOR_STALE` — HTTP 409

title: en “Cursor is stale” · ko “커서가 만료됨”

| why | what | how | opinion |
|---|---|---|---|
| paging across a release switch. | the cursor's data version differs from the pinned release. | 409, start again from the first page. | same as the prototype. |
