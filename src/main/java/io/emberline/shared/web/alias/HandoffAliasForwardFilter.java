package io.emberline.shared.web.alias;

import io.emberline.shared.error.EmberlineException;
import io.emberline.shared.web.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves the hand-off paths ({@code /api/<handoff path>}) by forwarding to the canonical /api/v1 route on the same
 * dispatcher, so validation, use case and caching are one path. Adds {@code Link: <canonical>; rel="canonical"}.
 * Aliases are not controllers, so springdoc never lists them. A request the rule refuses (e.g. one value under two
 * names, MAPPING §8.1, or {@code aoi} with {@code aoi_id}) is answered here as a problem, not forwarded.
 */
@Component
@Order(0)
public class HandoffAliasForwardFilter extends OncePerRequestFilter {

    private final HandoffAliasTable table;
    private final ProblemWriter problems;

    public HandoffAliasForwardFilter(HandoffAliasTable table, ProblemWriter problems) {
        this.table = table;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Optional<HandoffAliasRule> rule = table.find(path);
        if (rule.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<HandoffAliasRule.Rejection> refused = rule.get().rejection(request.getParameterMap());
        if (refused.isPresent()) {
            HandoffAliasRule.Rejection r = refused.get();
            problems.write(request, response, r.code(), r.param(), r.detail());
            return;
        }
        HandoffAliasRule.Canonical canonical;
        try {
            canonical = rule.get().rewrite(request.getParameterMap(), request.getQueryString());
        } catch (EmberlineException e) {
            problems.write(request, response, e.errorCode(), e.param(), e.detail());
            return;
        }
        response.setHeader(HttpHeaders.LINK, "<" + canonical.link() + ">; rel=\"canonical\"");
        request.getRequestDispatcher(canonical.path()).forward(new CanonicalRequest(request, canonical), response);
    }

    /** The original request seen with the canonical parameters. */
    static final class CanonicalRequest extends HttpServletRequestWrapper {
        private final HandoffAliasRule.Canonical canonical;

        CanonicalRequest(HttpServletRequest request, HandoffAliasRule.Canonical canonical) {
            super(request);
            this.canonical = canonical;
        }

        @Override
        public String getParameter(String name) {
            String[] v = canonical.params().get(name);
            return v == null || v.length == 0 ? null : v[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Collections.unmodifiableMap(canonical.params());
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(canonical.params().keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            return canonical.params().get(name);
        }

        @Override
        public String getQueryString() {
            return canonical.query();
        }
    }
}
