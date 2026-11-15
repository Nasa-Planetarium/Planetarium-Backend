package io.emberline.shared.web.http;

import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.error.Problem;
import io.emberline.shared.web.release.ReleaseRefFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Cache headers for successful GETs on a pinned release: ETag = sha256(path template, normalized query, negotiated
 * media type, data_version, method_version, response schema version); If-None-Match → 304 without body;
 * X-Method-Version, X-Data-Version, Vary and {@link #CACHE_CONTROL_DATA} ({@link #CACHE_CONTROL_VERSION} on /version,
 * the endpoint clients poll to notice an activation; {@link #CACHE_CONTROL_LAYER} on {@link LayerCached} methods).
 * Without a release (only /version answers then) the response is {@code no-cache}. Shared caches (pl-web proxy_cache)
 * key on the data_version, so s-maxage never outlives a release.
 */
@ControllerAdvice
public class HttpCachingAdvice implements ResponseBodyAdvice<Object> {

    /**
     * Bump when a response record changes shape, so old ETags stop matching. s2: renamed the {@code /hotspots} fields
     * ({@code adm0_a3}/{@code adm1_code} → {@code country}/{@code adm1}) and added {@code Method.display_names} /
     * {@code performance_copy} under unchanged data_versions. s3: the compare fields. s4: added the four
     * {@code forecast_*} members to {@code /version} {@code limits}.
     */
    static final String SCHEMA_VERSION = "s4";
    /** Release-pinned data: browsers reuse 60 s, shared caches 600 s (docs/DEVIATIONS.md PUB-CACHE). */
    public static final String CACHE_CONTROL_DATA = "public, max-age=60, s-maxage=600";
    /** /version names the active release: browsers revalidate every time, shared caches hold it 5 s. */
    public static final String CACHE_CONTROL_VERSION = "public, max-age=0, s-maxage=5, must-revalidate";
    /** v1.2 layers ({@link LayerCached}): browsers revalidate every time, shared caches hold 3600 s (contract CacheControlLayer). */
    public static final String CACHE_CONTROL_LAYER = "public, max-age=0, s-maxage=3600, must-revalidate";
    static final String VERSION_PATTERN = "/api/v1/version";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof byte[] || body instanceof Problem || !(request instanceof ServletServerHttpRequest servletRequest)
                || !(response instanceof ServletServerHttpResponse servletResponse)) {
            return body;
        }
        HttpServletRequest req = servletRequest.getServletRequest();
        HttpServletResponse res = servletResponse.getServletResponse();
        if (!"GET".equals(req.getMethod()) || res.getStatus() != HttpStatus.OK.value()) {
            return body;
        }
        HttpHeaders headers = response.getHeaders();
        headers.set(HttpHeaders.VARY, "Accept, Accept-Language");
        Optional<ReleaseRef> pinned = ReleaseRefFilter.pinned(req);
        if (pinned.isEmpty()) {
            headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
            return body;
        }
        ReleaseRef release = pinned.get();
        String etag = "\"" + etag(req, selectedContentType, release) + "\"";
        headers.set(HttpHeaders.ETAG, etag);
        headers.set("X-Method-Version", release.methodVersion());
        headers.set("X-Data-Version", release.dataVersion());
        headers.set(HttpHeaders.CACHE_CONTROL, cacheControl(req, returnType));
        if (matches(req.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            response.setStatusCode(HttpStatus.NOT_MODIFIED);
            headers.remove(HttpHeaders.CONTENT_TYPE);
            return null;
        }
        return body;
    }

    static String cacheControl(HttpServletRequest req, MethodParameter returnType) {
        if (returnType.hasMethodAnnotation(LayerCached.class)) {
            return CACHE_CONTROL_LAYER;
        }
        return VERSION_PATTERN.equals(req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                ? CACHE_CONTROL_VERSION : CACHE_CONTROL_DATA;
    }

    static String etag(HttpServletRequest req, MediaType mediaType, ReleaseRef release) {
        return etag(req, mediaType, release, SCHEMA_VERSION);
    }

    /** The ETag (without quotes) the response would carry under response schema {@code schemaVersion}. */
    static String etag(HttpServletRequest req, MediaType mediaType, ReleaseRef release, String schemaVersion) {
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object uriVars = req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String seed = String.join("\n", String.valueOf(pattern), String.valueOf(uriVars), normalizedQuery(req),
                mediaType == null ? "" : mediaType.getType() + "/" + mediaType.getSubtype(),
                release.dataVersion(), release.methodVersion(), schemaVersion);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String normalizedQuery(HttpServletRequest req) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
            String[] values = e.getValue().clone();
            Arrays.sort(values);
            for (String v : values) {
                parts.add(e.getKey() + "=" + v);
            }
        }
        parts.sort(String::compareTo);
        return String.join("&", parts);
    }

    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals("*") || c.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
