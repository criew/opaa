package io.opaa.indexing.source.s3.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the S3 event intake (ADR-0027, Entscheidung 6 and 11) - its own block
 * beside {@code S3Properties}, like the Confluence webhook's beside the access layer's.
 *
 * @param debounce how long the intake collects reported keys for one library before it starts the
 *     event run - a script uploading fifty objects costs one run, not fifty. Default 5 seconds;
 *     zero or negative falls back to it.
 * @param maxPendingKeys how many distinct keys one pending batch holds before the batch is turned
 *     into an ordinary full sync instead of single checks (a bulk import lists cheaper than it
 *     heads). Default 500; zero or negative falls back to it.
 * @param maxDeferrals how often a pending batch waits another {@code debounce} because a run for
 *     the library is already in progress before it is dropped - the next run covers what the
 *     notification reported, so a drop costs freshness, never correctness. Default 12 (one minute
 *     at the default debounce); zero or negative falls back to it.
 */
@ConfigurationProperties(prefix = "opaa.indexing.s3.events")
public record S3EventProperties(Duration debounce, int maxPendingKeys, int maxDeferrals) {

  public S3EventProperties {
    if (debounce == null || debounce.isZero() || debounce.isNegative()) {
      debounce = Duration.ofSeconds(5);
    }
    if (maxPendingKeys <= 0) {
      maxPendingKeys = 500;
    }
    if (maxDeferrals <= 0) {
      maxDeferrals = 12;
    }
  }
}
