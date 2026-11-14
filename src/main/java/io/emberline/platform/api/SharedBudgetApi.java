package io.emberline.platform.api;

import java.time.Duration;

/** Budgets shared by all pods (token buckets on redis-durable; pod-local buckets while redis-durable is down). */
public interface SharedBudgetApi {

    /** Takes one token from the budget {@code name} that refills {@code capacity} tokens every {@code period}. */
    BudgetDecision tryTake(String name, long capacity, Duration period);
}
