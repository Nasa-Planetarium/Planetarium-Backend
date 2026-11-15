package io.emberline.external.application.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code emberline.firms.*}.
 *
 * @param wmsBaseUrl     FIRMS_WMS_BASE_URL — scheme + host (+ port) of the only upstream
 * @param mapKeyFile     FIRMS_MAP_KEY_FILE — path of the Secret file holding the key (configtree); never the key
 * @param timeout        upstream connect + response timeout
 * @param cacheTtl       tile lifetime on redis-cache
 * @param budgetCapacity upstream requests (cache misses) allowed per {@code budgetPeriod}, shared by all pods
 * @param budgetPeriod   budget window
 * @param budgetPerCaller part of {@code budgetCapacity} one caller (rate-limit subject) may spend per
 *                       {@code budgetPeriod}, so one address cannot exhaust the key's budget; default = the whole
 *                       budget (no share). The public deployment sets 1500 of 4500 (docs/DEVIATIONS.md PUB-RL)
 * @param env            key namespace on redis ({@code em:{env}:wms:…})
 */
@ConfigurationProperties("emberline.firms")
public record FirmsProperties(String wmsBaseUrl, String mapKeyFile, Duration timeout, Duration cacheTtl,
                              Long budgetCapacity, Duration budgetPeriod, String env, Long budgetPerCaller) {

    public FirmsProperties {
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(900) : cacheTtl;
        budgetCapacity = budgetCapacity == null || budgetCapacity < 1 ? 4500L : budgetCapacity;
        budgetPeriod = budgetPeriod == null ? Duration.ofMinutes(10) : budgetPeriod;
        env = env == null || !env.matches("^[a-z0-9-]{1,32}$") ? "test" : env;
        budgetPerCaller = budgetPerCaller == null || budgetPerCaller < 1 ? budgetCapacity : Math.min(budgetPerCaller, budgetCapacity);
    }

    /** Whether one caller's misses are capped below the whole budget. */
    public boolean callerShare() {
        return budgetPerCaller < budgetCapacity;
    }
}
