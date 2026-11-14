package io.emberline.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter: accepts an incoming {@code X-Request-Id} only when it matches {@code ^[A-Za-z0-9-]{8,64}$},
 * otherwise makes a new one. The id goes to the response header, the MDC and every problem body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final char[] B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : newId();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put("request_id", id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("request_id");
        }
    }

    /** 16 Crockford base32 characters: 48 bits of milliseconds + 32 random bits. */
    static String newId() {
        long time = System.currentTimeMillis();
        long rnd = RANDOM.nextInt() & 0xffffffffL;
        char[] out = new char[16];
        for (int i = 9; i >= 0; i--) {
            out[i] = B32[(int) (time & 31)];
            time >>>= 5;
        }
        for (int i = 15; i >= 10; i--) {
            out[i] = B32[(int) (rnd & 31)];
            rnd >>>= 5;
        }
        return new String(out);
    }

    public static String of(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id == null ? newId() : id.toString();
    }
}
