package io.opaa.indexing.source;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the shared event intake ({@link SourceEventIntake}), one block for every
 * connector with a push path - Confluence webhooks and S3 event notifications alike.
 *
 * @param debounce how long the intake collects notifications for one library before it starts the
 *     targeted run - an author saving five times in a minute, a script uploading fifty objects,
 *     each costs one run. Default 5 seconds; zero or negative falls back to it.
 * @param maxPendingKeys how many distinct keys (page ids, {@code bucket/key} references) one
 *     pending batch holds before the batch is turned into the executor's ordinary run instead of
 *     targeted checks - a bulk import lists cheaper than it fetches one by one. Default 500; zero
 *     or negative falls back to it.
 * @param maxDeferrals how often a pending batch waits another {@code debounce} because a run for
 *     the library is already in progress before it is dropped - the next run covers what the
 *     notification reported, so a drop costs freshness, never correctness. Default 120 (ten minutes
 *     at the default debounce); zero or negative falls back to it.
 */
@ConfigurationProperties(prefix = "opaa.indexing.events")
public record SourceEventProperties(Duration debounce, int maxPendingKeys, int maxDeferrals) {

  public SourceEventProperties {
    if (debounce == null || debounce.isZero() || debounce.isNegative()) {
      debounce = Duration.ofSeconds(5);
    }
    if (maxPendingKeys <= 0) {
      maxPendingKeys = 500;
    }
    if (maxDeferrals <= 0) {
      maxDeferrals = 120;
    }
  }
}
