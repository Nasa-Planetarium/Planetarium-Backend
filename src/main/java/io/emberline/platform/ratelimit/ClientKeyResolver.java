package io.emberline.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Who a request is charged to: the TCP peer, or — only when the peer is a trusted proxy — the right-most
 * X-Forwarded-For address that is not itself a trusted proxy. IPv6 addresses are folded to their /64. Any other
 * client-IP header (X-Client-IP, CF-Connecting-IP, X-Real-IP) is never read here: pl-web nginx turns the one it trusts
 * into X-Forwarded-For.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class ClientKeyResolver {

    private static final Pattern LITERAL = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final List<Cidr> trusted;
    private final SecretKeySpec hmacKey;

    public ClientKeyResolver(RateLimitProperties properties) {
        this.trusted = properties.trustedProxies().stream().map(Cidr::parse).toList();
        this.hmacKey = new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** The subject as it appears in bucket keys: an HMAC, so the client address never does. */
    public String key(HttpServletRequest request) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(subject(request).getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public String subject(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        InetAddress client = literal(peer);
        if (client != null && isTrusted(client)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null) {
                String[] hops = xff.split(",");
                for (int i = hops.length - 1; i >= 0; i--) {
                    InetAddress hop = literal(hops[i].strip());
                    if (hop == null) {
                        break;
                    }
                    client = hop;
                    if (!isTrusted(hop)) {
                        break;
                    }
                }
            }
        }
        return client == null ? String.valueOf(peer) : fold(client);
    }

    private boolean isTrusted(InetAddress a) {
        return trusted.stream().anyMatch(c -> c.contains(a));
    }

    /** IPv4 as is; IPv6 as its /64 network. */
    static String fold(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 4) {
            return a.getHostAddress();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff))).append(':');
        }
        return sb.append(":/64").toString();
    }

    /** Parses an IP literal only (never a DNS lookup); null if it is not one. */
    static InetAddress literal(String s) {
        if (s == null) {
            return null;
        }
        String v = s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
        if (!LITERAL.matcher(v).matches() || (!v.contains(":") && !v.matches("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
            return null;
        }
        try {
            return InetAddress.getByName(v);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** An address or a network (a.b.c.d/n, x::/n). */
    record Cidr(byte[] network, int bits) {

        static Cidr parse(String s) {
            String[] parts = s.split("/", 2);
            InetAddress a = literal(parts[0]);
            if (a == null) {
                throw new IllegalArgumentException("emberline.ratelimit.trusted-proxies: not an IP or CIDR");
            }
            int max = a.getAddress().length * 8;
            int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : max;
            if (bits < 0 || bits > max) {
                throw new IllegalArgumentException("emberline.ratelimit.trusted-proxies: bad prefix length");
            }
            return new Cidr(a.getAddress(), bits);
        }

        boolean contains(InetAddress a) {
            byte[] b = a.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (b[i] != network[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xff << (8 - rest)) & 0xff;
            return (b[full] & mask) == (network[full] & mask);
        }
    }
}
