package io.emberline.platform.api;

/** Whether a token was granted; otherwise the whole seconds until the next refill (≥ 1). */
public record BudgetDecision(boolean granted, long retryAfterSeconds) {
}
