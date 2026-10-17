package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer counters of the rate limits: one series per limit and scope, so a dashboard can tell a
 * throttled client ({@code scope=client}, {@code subject}, {@code address}) from the global ceiling
 * ({@code scope=global}) that ADR-0033 names the operator's early warning against distributed
 * credential stuffing.
 */
public class RateLimitMetrics {

  public static final String REJECTED_METRIC = "opaa.rate_limit.rejected";

  private final MeterRegistry meterRegistry;

  public RateLimitMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * @param limit the rule's name (e.g. {@code query}, {@code local-auth-login})
   * @param scope what was exhausted: {@code client}, {@code global}, {@code subject} or {@code
   *     address}
   */
  public void recordRejected(String limit, String scope) {
    Counter.builder(REJECTED_METRIC)
        .tag("limit", limit)
        .tag("scope", scope)
        .description("Requests refused by a rate limit, by limit and exhausted scope")
        .register(meterRegistry)
        .increment();
  }
}
