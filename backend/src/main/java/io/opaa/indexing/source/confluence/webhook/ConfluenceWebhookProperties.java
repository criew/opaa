package io.opaa.indexing.source.confluence.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the Confluence webhook intake - its own block beside {@code
 * ConfluenceProperties} so adding a bound here touches no positional call site of the access layer.
 *
 * @param debounce how long the intake collects notifications for one library before it starts the
 *     targeted run - an author saving five times in a minute costs one run, not five. Default 5
 *     seconds; zero or negative falls back to it.
 * @param maxPendingPages how many distinct pages one pending batch holds before the batch is turned
 *     into an ordinary incremental run instead of targeted fetches (a bulk import or a script would
 *     otherwise queue thousands of single fetches). Default 200; zero or negative falls back to it.
 * @param maxDeferrals how often a pending batch waits another {@code debounce} because a run for
 *     the library is already in progress before it is dropped - the next scheduled or incremental
 *     run covers what the webhook reported, so a drop costs freshness, never correctness. Default
 *     120 (ten minutes at the default debounce); zero or negative falls back to it.
 */
@ConfigurationProperties(prefix = "opaa.indexing.confluence.webhook")
public record ConfluenceWebhookProperties(
    Duration debounce, int maxPendingPages, int maxDeferrals) {

  public ConfluenceWebhookProperties {
    if (debounce == null || debounce.isZero() || debounce.isNegative()) {
      debounce = Duration.ofSeconds(5);
    }
    if (maxPendingPages <= 0) {
      maxPendingPages = 200;
    }
    if (maxDeferrals <= 0) {
      maxDeferrals = 120;
    }
  }
}
