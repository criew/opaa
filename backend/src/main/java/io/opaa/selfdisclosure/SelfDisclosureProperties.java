package io.opaa.selfdisclosure;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.self-disclosure.*}: the load bounds of the self-disclosure. A value outside its range
 * refuses the start, naming the variable.
 *
 * @param maxConcurrentExports exports this instance streams at the same time, 1 to 16 (default 2);
 *     above it the export is answered with {@code 503}
 * @param maxDuration how long one export may stream before it is aborted, 5 minutes to 2 hours
 *     (default 30 minutes)
 */
@NoDataImpact("load bounds of the self-disclosure; they keep no data")
@ConfigurationProperties(prefix = "opaa.self-disclosure")
public record SelfDisclosureProperties(
    @NoDataImpact("a bound of concurrent work; it keeps no data")
        @PerInstance("a bound of this process's threads and memory, like the worker pools")
        Integer maxConcurrentExports,
    @NoDataImpact("how long one export may stream; it keeps no data") @InstallationWide
        Duration maxDuration) {

  static final String MAX_CONCURRENT_EXPORTS_VARIABLE =
      "OPAA_SELF_DISCLOSURE_MAX_CONCURRENT_EXPORTS";
  static final String MAX_DURATION_VARIABLE = "OPAA_SELF_DISCLOSURE_MAX_DURATION";

  public SelfDisclosureProperties {
    maxConcurrentExports = maxConcurrentExports != null ? maxConcurrentExports : 2;
    maxDuration = maxDuration != null ? maxDuration : Duration.ofMinutes(30);
    if (maxConcurrentExports < 1 || maxConcurrentExports > 16) {
      throw new IllegalArgumentException(
          "opaa.self-disclosure.max-concurrent-exports ("
              + MAX_CONCURRENT_EXPORTS_VARIABLE
              + ") must lie between 1 and 16, got "
              + maxConcurrentExports);
    }
    if (maxDuration.compareTo(Duration.ofMinutes(5)) < 0
        || maxDuration.compareTo(Duration.ofHours(2)) > 0) {
      throw new IllegalArgumentException(
          "opaa.self-disclosure.max-duration ("
              + MAX_DURATION_VARIABLE
              + ") must lie between 5 minutes and 2 hours, got "
              + maxDuration);
    }
  }
}
