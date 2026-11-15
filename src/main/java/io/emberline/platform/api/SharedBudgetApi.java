package io.emberline.platform.api;

import java.time.Duration;

/** Budgets shared by all pods (token buckets on redis-durable; pod-local buckets while redis-durable is down). */
public interface SharedBudgetApi {

    /** Takes one token from the budget {@code name} that refills {@code capacity} tokens every {@code period}. */
    BudgetDecision tryTake(String name, long capacity, Duration period);

    /**
     * Takes one token from the share of {@code name} that belongs to the current HTTP caller (the rate-limit subject:
     * client address, forwarded only by trusted proxies) and refills {@code capacity} tokens every {@code period}.
     * Outside an HTTP request, or with rate limiting off ({@code emberline.ratelimit.enabled=false}), it always grants.
     */
    BudgetDecision tryTakeForCaller(String name, long capacity, Duration period);
}
