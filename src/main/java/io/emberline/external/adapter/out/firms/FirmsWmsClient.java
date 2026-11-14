package io.emberline.external.adapter.out.firms;

import io.emberline.external.application.port.out.WmsUpstreamPort;
import io.emberline.external.application.service.FirmsProperties;
import io.emberline.external.domain.FirmsKey;
import io.emberline.external.domain.WmsTile;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.UpstreamException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FIRMS WMS GetMap over the JDK HttpClient: fixed scheme/host/port from FIRMS_WMS_BASE_URL, path
 * {@code /mapserver/wms/fires/<key>/}, query rebuilt from the validated tile only, redirects off, timeout 5 s.
 * Upstream bodies, URLs and exception messages are never logged or returned (they may contain the key).
 */
@Component
class FirmsWmsClient implements WmsUpstreamPort {

    private static final Logger log = LoggerFactory.getLogger(FirmsWmsClient.class);
    static final int MAX_BYTES = 8 * 1024 * 1024;

    private final FirmsProperties properties;
    private final HttpClient http;
    private final URI base;

    FirmsWmsClient(FirmsProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.timeout()).build();
        this.base = base(properties.wmsBaseUrl());
    }

    @Override
    public byte[] fetch(WmsTile tile, FirmsKey key) {
        if (base == null) {
            throw new UpstreamException(ErrorCode.UPSTREAM_UNAVAILABLE, "the FIRMS map layer is not configured on this server");
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("SERVICE", "WMS");
        q.put("VERSION", "1.1.1");
        q.put("REQUEST", "GetMap");
        q.put("LAYERS", tile.firmsLayer());
        q.put("STYLES", "");
        q.put("SRS", "EPSG:4326");
        q.put("BBOX", tile.bboxParam());
        q.put("WIDTH", Integer.toString(tile.width()));
        q.put("HEIGHT", Integer.toString(tile.height()));
        q.put("FORMAT", "image/png");
        q.put("TRANSPARENT", "TRUE");
        if (tile.time() != null) {
            q.put("TIME", tile.time());
        }
        String query = q.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        URI uri = base.resolve("/mapserver/wms/fires/" + enc(key.reveal()) + "/?" + query);
        HttpResponse<byte[]> response;
        try {
            response = http.send(HttpRequest.newBuilder(uri).timeout(properties.timeout())
                    .header("User-Agent", "emberline-api").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            log.warn("firms wms upstream timeout");
            throw new UpstreamException(ErrorCode.UPSTREAM_UNAVAILABLE, "the FIRMS map layer did not answer in time");
        } catch (IOException e) {
            log.warn("firms wms upstream failure ({})", e.getClass().getSimpleName());
            throw new UpstreamException(ErrorCode.UPSTREAM_ERROR, "the FIRMS map request failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(ErrorCode.UPSTREAM_UNAVAILABLE, "the FIRMS map request was interrupted");
        }
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() != 200) {
            log.warn("firms wms upstream status {}", response.statusCode());
            throw new UpstreamException(ErrorCode.UPSTREAM_ERROR, "the FIRMS map request failed (upstream status "
                    + response.statusCode() + ")");
        }
        if (!type.startsWith("image/") || response.body().length > MAX_BYTES) {
            log.warn("firms wms upstream returned no image");
            throw new UpstreamException(ErrorCode.UPSTREAM_ERROR, "the FIRMS map request returned no image");
        }
        return response.body();
    }

    /** scheme://host[:port] only; anything else (path, query, user info) makes the proxy unconfigured. */
    static URI base(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(raw.strip().replaceAll("/+$", ""));
            boolean ok = ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost() != null
                    && u.getUserInfo() == null && u.getQuery() == null && (u.getPath() == null || u.getPath().isEmpty());
            return ok ? URI.create(u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/") : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
