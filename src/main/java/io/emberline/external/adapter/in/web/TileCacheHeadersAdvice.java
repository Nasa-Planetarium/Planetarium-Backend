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
 * Tiles are not release data: no ETag/304 on the release, but a short shared cache matching the 15-minute tile
 * lifetime ({@code public, max-age=900}).
 */
@ControllerAdvice(assignableTypes = WmsTileController.class)
class TileCacheHeadersAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getParameterType() == byte[].class;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof byte[]) {
            response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "public, max-age=900");
            response.getHeaders().remove(HttpHeaders.ETAG);
        }
        return body;
    }
}
