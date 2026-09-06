package io.opaa.indexing;

import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.Sleeper;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What every request to a source OPAA does not operate carries and tolerates - the RSS feed and its
 * pages and attachments, the web directory, Confluence: one {@code User-Agent} and one rate-limit
 * tolerance for the deployment. Its own property block, not a component of {@link
 * IndexingProperties}, which is bound positionally by many call sites.
 *
 * @param userAgent the {@code User-Agent} header sent with every request. Truthful by default;
 *     impersonating a browser is out of scope. Default {@code OPAA-Indexer/1.0}.
 * @param maxRateLimitRetries how many consecutive {@code 429} responses one request waits out (each
 *     after {@code Retry-After}, five seconds without it) before the last {@code 429} reaches the
 *     caller. Default 6. Confluence keeps its own value ({@code
 *     opaa.indexing.confluence.max-rate-limit-retries}).
 * @param maxRetryAfter the longest single wait honoured from a {@code Retry-After}; a longer value
 *     is capped to this. Default 2 minutes. Confluence keeps its own value ({@code
 *     opaa.indexing.confluence.max-retry-after}).
 */
@ConfigurationProperties(prefix = "opaa.indexing.http")
public record SourceHttpProperties(
    String userAgent, int maxRateLimitRetries, Duration maxRetryAfter) {

  public SourceHttpProperties {
    if (userAgent == null || userAgent.isBlank()) {
      userAgent = SourceRequestPolicy.DEFAULT_USER_AGENT;
    }
    if (maxRateLimitRetries < 0) {
      throw new IllegalArgumentException(
          "maxRateLimitRetries must not be negative, got " + maxRateLimitRetries);
    }
    if (maxRateLimitRetries == 0) {
      maxRateLimitRetries = SourceRequestPolicy.DEFAULT_MAX_RATE_LIMIT_RETRIES;
    }
    if (maxRetryAfter == null || maxRetryAfter.isZero() || maxRetryAfter.isNegative()) {
      maxRetryAfter = SourceRequestPolicy.DEFAULT_MAX_RETRY_AFTER;
    }
  }

  /**
   * The policy every source-access caller of this deployment shares. {@code maxRateLimitRetries} is
   * never {@code 0} here - the compact constructor maps {@code 0} to the default 6, so the {@code
   * 429} wait cannot be switched off, only shortened via {@code maxRetryAfter}.
   */
  public SourceRequestPolicy toRequestPolicy() {
    return new SourceRequestPolicy(
        userAgent, RateLimitPolicy.of(maxRateLimitRetries, maxRetryAfter), Sleeper.threadSleep());
  }
}
