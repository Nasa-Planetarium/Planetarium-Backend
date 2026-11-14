package io.emberline.shared.web.release;

import io.emberline.shared.release.ActiveReleaseSource;
import io.emberline.shared.release.ReleaseRef;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pins the pod's active release once at request start. Requests that started before a switch finish on the old
 * release; whether a route needs a release is decided by its controller signature ({@link ReleaseRefArgumentResolver}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ReleaseRefFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = ReleaseRefFilter.class.getName() + ".release";

    private final ObjectProvider<ActiveReleaseSource> source;

    public ReleaseRefFilter(ObjectProvider<ActiveReleaseSource> source) {
        this.source = source;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ActiveReleaseSource active = source.getIfAvailable();
        Optional<ReleaseRef> release = active == null ? Optional.empty() : active.current();
        release.ifPresent(r -> {
            request.setAttribute(ATTRIBUTE, r);
            MDC.put("release_id", Long.toString(r.releaseId()));
        });
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("release_id");
        }
    }

    public static Optional<ReleaseRef> pinned(HttpServletRequest request) {
        return Optional.ofNullable((ReleaseRef) request.getAttribute(ATTRIBUTE));
    }
}
