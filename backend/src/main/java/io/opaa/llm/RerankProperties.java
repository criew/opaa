package io.opaa.llm;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The load limits of the rerank model role. Switch, address, model and key are the administration's
 * ({@link RerankEndpoint}, ADR-0060, Entscheidung 10).
 *
 * @param timeout per-request budget for one rerank call. Default {@value #DEFAULT_TIMEOUT_SECONDS}
 *     seconds: a measured three minutes per query for the default candidate window (50) on a
 *     20-core CPU with {@code BAAI/bge-reranker-v2-m3}. A shorter default degrades every such query
 *     to {@link RerankRoleState#UNREACHABLE} on a schedule, not on a fault; an installation with a
 *     faster (typically GPU-backed) endpoint may lower {@code OPAA_RERANK_TIMEOUT}.
 * @param probeInterval the longest the role goes without a result from its endpoint before it
 *     probes, {@code OPAA_RERANK_PROBE_INTERVAL}. Every probe is a real rerank request and is
 *     billed by a paid endpoint; a real call resets the interval. At least {@value
 *     #MIN_PROBE_INTERVAL_SECONDS} seconds, anything shorter fails the start.
 */
@PerInstance("timeouts and probing of this process")
@NoDataImpact("timeouts and probing of the rerank role")
@ConfigurationProperties(prefix = "opaa.rerank")
public record RerankProperties(
    @DefaultValue(DEFAULT_TIMEOUT_SECONDS + "s") @NoDataImpact("a timeout of one request")
        Duration timeout,
    @DefaultValue(DEFAULT_PROBE_INTERVAL_SECONDS + "s")
        @NoDataImpact("a probe sends a fixed test document, no user content")
        Duration probeInterval) {

  /** See {@link #timeout}. */
  static final int DEFAULT_TIMEOUT_SECONDS = 240;

  /** See {@link #probeInterval}. */
  static final int DEFAULT_PROBE_INTERVAL_SECONDS = 60;

  /** See {@link #probeInterval}. */
  static final int MIN_PROBE_INTERVAL_SECONDS = 10;

  public RerankProperties {
    timeout = positiveOr(timeout, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    if (probeInterval == null) {
      probeInterval = Duration.ofSeconds(DEFAULT_PROBE_INTERVAL_SECONDS);
    }
    if (probeInterval.compareTo(Duration.ofSeconds(MIN_PROBE_INTERVAL_SECONDS)) < 0) {
      throw new IllegalArgumentException(
          "opaa.rerank.probe-interval must be at least "
              + MIN_PROBE_INTERVAL_SECONDS
              + "s, was "
              + probeInterval);
    }
  }
}
