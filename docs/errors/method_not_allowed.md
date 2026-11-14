# `method_not_allowed`

problem `type`: `https://emberline.example/errors/method_not_allowed` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 10002 `METHOD_NOT_ALLOWED` — HTTP 405

title: en “Method not allowed” · ko “허용되지 않는 메서드”

| why | what | how | opinion |
|---|---|---|---|
| a wrong client or probing. | MVC built-in HttpRequestMethodNotSupportedException. | 405 with an `Allow` header. | built-in MVC errors use the same problem shape (ResponseEntityExceptionHandler). |
