package io.emberline.external.application.service;

import io.emberline.external.application.port.in.GetWmsTileUseCase;
import io.emberline.external.application.port.in.WmsTileQuery;
import io.emberline.external.application.port.out.FirmsKeyPort;
import io.emberline.external.application.port.out.TileCachePort;
import io.emberline.external.application.port.out.WmsUpstreamPort;
import io.emberline.external.domain.FirmsKey;
import io.emberline.external.domain.WmsTile;
import io.emberline.platform.api.BudgetDecision;
import io.emberline.platform.api.SharedBudgetApi;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.error.UpstreamException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * FIRMS WMS proxy: validate → clip to Korea → key present? → redis-cache (900 s) → the caller's share of the budget →
 * global budget (cache misses only, redis-durable) → upstream → cache. The cache key is a hash of the rebuilt request; it holds no key material.
 */
@Service
@EnableConfigurationProperties(FirmsProperties.class)
class WmsTileService implements GetWmsTileUseCase {

    static final String BUDGET = "firms-wms";

    private final FirmsKeyPort keys;
    private final TileCachePort cache;
    private final WmsUpstreamPort upstream;
    private final SharedBudgetApi budgets;
    private final FirmsProperties properties;

    WmsTileService(FirmsKeyPort keys, TileCachePort cache, WmsUpstreamPort upstream, SharedBudgetApi budgets,
                   FirmsProperties properties) {
        this.keys = keys;
        this.cache = cache;
        this.upstream = upstream;
        this.budgets = budgets;
        this.properties = properties;
    }

    @Override
    public byte[] tile(WmsTileQuery query) {
        double[] b = bbox(query.bbox());
        WmsTile tile = WmsTile.clipped(query.layer(), b[0], b[1], b[2], b[3], query.width(), query.height(), query.time())
                .orElseThrow(() -> new InvalidRequestException(ErrorCode.INVALID_PARAM, "bbox",
                        "bbox does not overlap the supported Korea area 124.5,33,132,38.7"));
        FirmsKey key = keys.key().orElseThrow(() -> new UpstreamException(ErrorCode.UPSTREAM_UNAVAILABLE,
                "the FIRMS map layer is not configured on this server"));
        String cacheKey = "em:" + properties.env() + ":wms:" + sha256(tile.identity());
        Optional<byte[]> hit = cache.get(cacheKey);
        if (hit.isPresent()) {
            return hit.get();
        }
        if (properties.callerShare()
                && !budgets.tryTakeForCaller(BUDGET, properties.budgetPerCaller(), properties.budgetPeriod()).granted()) {
            throw new UpstreamException(ErrorCode.UPSTREAM_UNAVAILABLE, "the FIRMS map layer is busy for this address; try again later");
        }
        BudgetDecision budget = budgets.tryTake(BUDGET, properties.budgetCapacity(), properties.budgetPeriod());
        if (!budget.granted()) {
            throw new UpstreamException(ErrorCode.UPSTREAM_UNAVAILABLE, "the FIRMS map layer is busy; try again later");
        }
        byte[] png = upstream.fetch(tile, key);
        cache.put(cacheKey, png, properties.cacheTtl());
        return png;
    }

    /** {@code w,s,e,n}: four finite numbers with w &lt; e, s &lt; n inside ±180 / ±90. */
    static double[] bbox(String raw) {
        String[] parts = raw.split(",", -1);
        double[] v = new double[4];
        boolean ok = parts.length == 4;
        for (int i = 0; ok && i < 4; i++) {
            try {
                v[i] = Double.parseDouble(parts[i].strip());
                ok = Double.isFinite(v[i]);
            } catch (NumberFormatException e) {
                ok = false;
            }
        }
        if (!ok || !(-180 <= v[0] && v[0] < v[2] && v[2] <= 180 && -90 <= v[1] && v[1] < v[3] && v[3] <= 90)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "bbox",
                    "bbox must be west,south,east,north in degrees with west < east and south < north");
        }
        return v;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
