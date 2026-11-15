package io.emberline.shared.web.error;

import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.RequestIdFilter;
import io.emberline.shared.web.release.ReleaseRefFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

/** Builds problem bodies and the headers every problem carries (X-Request-Id, Cache-Control no-store, Retry-After). */
@Component
public class ProblemFactory {

    /** Codes that tell the client when to come back. */
    private static final Map<ErrorCode, String> RETRY_AFTER = Map.of(
            ErrorCode.DATA_NOT_LOADED, "60",
            ErrorCode.LAYER_NOT_LOADED, "60",
            ErrorCode.OVERLOADED, "5",
            ErrorCode.UPSTREAM_UNAVAILABLE, "60",
            ErrorCode.FORECAST_UNAVAILABLE, "30");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;
    private final String typeBase;

    public ProblemFactory(MessageSource messages, LocaleResolver localeResolver, EmberlineProperties properties) {
        this.messages = messages;
        this.localeResolver = localeResolver;
        this.typeBase = properties.errors().typeBase();
    }

    public Problem build(HttpServletRequest request, ErrorCode code, String param, String detail,
                         List<Problem.FieldProblem> errors) {
        Locale locale = localeResolver.resolveLocale(request);
        String title = messages.getMessage(code.titleKey(), null, code.code(), locale);
        ReleaseRef release = ReleaseRefFilter.pinned(request).orElse(null);
        return new Problem(typeBase + "/" + code.code(), title, code.httpStatus(), detail, instance(request),
                code.code(), code.number(), param, RequestIdFilter.of(request),
                release == null ? null : release.methodVersion(), errors == null || errors.isEmpty() ? null : errors);
    }

    public HttpHeaders headers(HttpServletRequest request, ErrorCode code) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, Problem.MEDIA_TYPE);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.set(RequestIdFilter.HEADER, RequestIdFilter.of(request));
        String retry = RETRY_AFTER.get(code);
        if (retry != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retry);
        }
        return headers;
    }

    private static String instance(HttpServletRequest request) {
        Object original = request.getAttribute("jakarta.servlet.forward.request_uri");
        return original != null ? original.toString() : request.getRequestURI();
    }
}
