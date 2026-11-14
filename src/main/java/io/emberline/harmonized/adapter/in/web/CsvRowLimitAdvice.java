package io.emberline.harmonized.adapter.in.web;

import io.emberline.harmonized.application.port.in.CsvTable;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Synchronous CSV is capped at {@code emberline.limits.csv-rows}: when text/csv was negotiated and the table has more
 * rows, 400 range_too_large (30102) before anything is written. Runs before the caching advice (highest precedence).
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class CsvRowLimitAdvice implements ResponseBodyAdvice<Object> {

    private final int maxRows;

    CsvRowLimitAdvice(EmberlineProperties properties) {
        this.maxRows = properties.limits().csvRows();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return CsvTable.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof CsvTable table && selectedContentType != null
                && "text".equals(selectedContentType.getType()) && "csv".equals(selectedContentType.getSubtype())
                && table.csvRows().size() > maxRows) {
            throw new InvalidRequestException(ErrorCode.CSV_ROWS_TOO_MANY, null, "CSV is limited to " + maxRows
                    + " rows per request; narrow the range (fewer years or a shorter start..end) and download in parts");
        }
        return body;
    }
}
