package io.emberline.external.adapter.in.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Tiles are not release data but live upstream images (redis-cache keeps one 15 minutes): no ETag/304 on the release
 * and a policy shorter than release data, {@code public, max-age=60, s-maxage=60} (docs/DEVIATIONS.md PUB-CACHE).
 */
@ControllerAdvice(assignableTypes = WmsTileController.class)
class TileCacheHeadersAdvice implements ResponseBodyAdvice<Object> {

    static final String CACHE_CONTROL_TILE = "public, max-age=60, s-maxage=60";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getParameterType() == byte[].class;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof byte[]) {
            response.getHeaders().set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_TILE);
            response.getHeaders().remove(HttpHeaders.ETAG);
        }
        return body;
    }
}
