package io.emberline.outlook.adapter.in.web;

import io.emberline.outlook.application.port.in.LayerBody;
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
 * Synchronous CSV of an outlook layer is capped at {@code emberline.limits.csv-rows} (contract parameter Format):
 * when text/csv was negotiated and the body has more rows, 400 range_too_large (30102) before anything is written.
 * Runs before the caching advice (highest precedence). The whole grid (52,855 rows) is under the 100,000 limit.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class LayerCsvLimitAdvice implements ResponseBodyAdvice<Object> {

    private final int maxRows;

    LayerCsvLimitAdvice(EmberlineProperties properties) {
        this.maxRows = properties.limits().csvRows();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return LayerBody.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof LayerBody layer && selectedContentType != null
                && "text".equals(selectedContentType.getType()) && "csv".equals(selectedContentType.getSubtype())
                && layer.csvRows() > maxRows) {
            throw new InvalidRequestException(ErrorCode.CSV_ROWS_TOO_MANY, null, "CSV is limited to " + maxRows
                    + " rows per request; narrow the area (bbox, min_p_any) and download in parts");
        }
        return body;
    }
}
