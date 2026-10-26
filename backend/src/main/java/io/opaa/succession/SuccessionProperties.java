package io.opaa.succession;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The schedule of the lifecycle (ADR-0036, Entscheidung 6); the aging threshold is a setting of the
 * administration ({@link SuccessionAgingThreshold}).
 *
 * @param detectionCron when the detection run looks - hourly by default. It writes only the
 *     timestamps: without a named, regular run "age" would mean "since somebody last looked"
 */
@PerInstance("when this process looks for open successions")
@NoDataImpact("the detection schedule")
@ConfigurationProperties(prefix = "opaa.succession")
public record SuccessionProperties(String detectionCron) {

  public SuccessionProperties {
    if (detectionCron == null || detectionCron.isBlank()) {
      detectionCron = "0 5 * * * *";
    }
  }
}
