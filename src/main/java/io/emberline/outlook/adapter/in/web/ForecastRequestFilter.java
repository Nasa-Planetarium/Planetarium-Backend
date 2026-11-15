package io.emberline.outlook.adapter.in.web;

import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.web.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code POST /api/v1/forecast} (and its hand-off alias {@code /api/forecast}) before MVC: the body is read here, at
 * most {@code emberline.limits.forecast-body-bytes} — a larger {@code Content-Length}, or a body that turns out larger
 * while reading, is answered 413 {@code FORECAST_BODY_TOO_LARGE} (40110) without parsing. The request goes on with the
 * buffered body. Every response of the route is {@code Cache-Control: no-store} (a POST the CDN does not cache; errors
 * are no-store anyway). Runs after the rate limit (a refused caller's body is never read) and before the alias forward.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ForecastRequestFilter extends OncePerRequestFilter {

    static final Set<String> PATHS = Set.of("/api/v1/forecast", "/api/forecast");

    private final int maxBytes;
    private final ProblemWriter problems;

    ForecastRequestFilter(EmberlineProperties properties, ProblemWriter problems) {
        this.maxBytes = properties.limits().forecastBodyBytes();
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        byte[] body = request.getContentLengthLong() > maxBytes ? null : request.getInputStream().readNBytes(maxBytes + 1);
        if (body == null || body.length > maxBytes) {
            problems.write(request, response, ErrorCode.FORECAST_BODY_TOO_LARGE, null,
                    "request body is limited to " + maxBytes + " bytes");
            return;
        }
        chain.doFilter(new BufferedBody(request, body), response);
    }

    /** The request with its body already read. */
    static final class BufferedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous body only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
