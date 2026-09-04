package io.opaa.library;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds the synchronous re-extraction of attachment bytes an "Im Dokument öffnen" click triggers
 * (#1243) - see {@link AttachmentExtractionLimiter} for what the two values actually guard.
 *
 * @param maxConcurrent how many attachment re-extractions may run at the same time across the whole
 *     instance. Default 4: each one holds one parent original plus one temp file per chain level,
 *     so this - not the number of concurrent requests - is what bounds the temporary disk this path
 *     can occupy. Valid range: 1-64.
 * @param acquireTimeout how long a request waits for its turn before it is answered with 503
 *     instead. Default 10s: long enough that an ordinary burst of clicks queues rather than fails,
 *     short enough that a caller never holds a request thread indefinitely behind a slow
 *     extraction.
 */
@ConfigurationProperties(prefix = "opaa.documents.attachment-extraction")
public record AttachmentExtractionProperties(int maxConcurrent, Duration acquireTimeout) {

  public AttachmentExtractionProperties {
    if (maxConcurrent <= 0) {
      maxConcurrent = 4;
    }
    if (maxConcurrent > 64) {
      throw new IllegalArgumentException("maxConcurrent must be at most 64, got " + maxConcurrent);
    }
    if (acquireTimeout == null || acquireTimeout.isNegative() || acquireTimeout.isZero()) {
      acquireTimeout = Duration.ofSeconds(10);
    }
  }
}
