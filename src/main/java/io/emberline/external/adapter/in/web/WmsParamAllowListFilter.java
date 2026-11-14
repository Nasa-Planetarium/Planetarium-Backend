package io.emberline.external.adapter.in.web;

import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.web.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The WMS proxy takes only {@code layer, bbox, width, height, time}, each once. Any other parameter (e.g. {@code url},
 * {@code SERVICE}) → 400 invalid_param naming it, before anything reaches the upstream.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class WmsParamAllowListFilter extends OncePerRequestFilter {

    static final Set<String> PATHS = Set.of("/api/v1/firms/wms-tiles", "/api/firms/wms");
    static final Set<String> ALLOWED = Set.of("layer", "bbox", "width", "height", "time");

    private final ProblemWriter problems;

    WmsParamAllowListFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Map.Entry<String, String[]> e : new java.util.TreeMap<>(request.getParameterMap()).entrySet()) {
            String name = e.getKey();
            if (!ALLOWED.contains(name)) {
                problems.write(request, response, ErrorCode.INVALID_PARAM, safe(name), "parameter " + safe(name)
                        + " is not accepted; allowed: " + String.join(", ", new TreeSet<>(ALLOWED)));
                return;
            }
            if (e.getValue().length > 1) {
                problems.write(request, response, ErrorCode.INVALID_PARAM, name, name + " must be given once");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Echo a parameter name only if it is a plain short word. */
    private static String safe(String name) {
        return name.matches("^[A-Za-z0-9_.\\-]{1,40}$") ? name : "parameter";
    }
}
