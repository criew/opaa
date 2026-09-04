package io.opaa.library;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds the synchronous re-extraction of attachment bytes an "Im Dokument öffnen" click triggers
 * (#1243) - see {@link AttachmentExtractionLimiter} for what the two values actually guard.
 *
 * @param maxConcurrent how many attachment re-extractions may <em>run</em> at the same time across
 *     the whole instance - it bounds the parsing and downloading, not how long an already extracted
 *     file stays on disk waiting to be streamed out (see {@link AttachmentExtractionLimiter}).
 *     Default 4 (applied when the property is absent). Valid range: 1-64; a configured value
 *     outside it fails startup rather than being silently corrected.
 * @param acquireTimeout how long a request waits for each of the limiter's two guards before it is
 *     answered with 429 instead - so at most twice this value in total. Default 10s (applied when
 *     the property is absent): long enough that an ordinary burst of clicks queues rather than
 *     fails, short enough that a caller never holds a request thread indefinitely behind a slow
 *     extraction. Must be positive.
 */
@ConfigurationProperties(prefix = "opaa.documents.attachment-extraction")
public record AttachmentExtractionProperties(int maxConcurrent, Duration acquireTimeout) {

  /** The values used when the property block is absent entirely. */
  public static final int DEFAULT_MAX_CONCURRENT = 4;

  public static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);

  public AttachmentExtractionProperties {
    // 0 is what binding produces for an absent property, so it means "unset" here; every other
    // out-of-range value is a real misconfiguration and fails startup instead of being corrected.
    if (maxConcurrent == 0) {
      maxConcurrent = DEFAULT_MAX_CONCURRENT;
    }
    if (maxConcurrent < 1 || maxConcurrent > 64) {
      throw new IllegalArgumentException(
          "maxConcurrent must be between 1 and 64, got " + maxConcurrent);
    }
    if (acquireTimeout == null) {
      acquireTimeout = DEFAULT_ACQUIRE_TIMEOUT;
    }
    if (acquireTimeout.isNegative() || acquireTimeout.isZero()) {
      throw new IllegalArgumentException("acquireTimeout must be positive, got " + acquireTimeout);
    }
  }
}
