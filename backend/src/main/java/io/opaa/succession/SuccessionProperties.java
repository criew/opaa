package io.opaa.succession;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two numbers the lifecycle knows (ADR-0036, Entscheidung 6).
 *
 * @param agingThresholdMonths from which age an entry of the operational list is highlighted.
 *     Default 12 months, taken from the maximum validity of the Vollmacht; it highlights, it never
 *     triggers anything
 * @param detectionCron when the detection run looks - hourly by default. It writes only the
 *     timestamps: without a named, regular run "age" would mean "since somebody last looked"
 */
@PerInstance("when this process looks for open successions")
@NoDataImpact("the detection schedule")
@ConfigurationProperties(prefix = "opaa.succession")
public record SuccessionProperties(@DataImpact int agingThresholdMonths, String detectionCron) {

  public SuccessionProperties {
    agingThresholdMonths = positiveOr(agingThresholdMonths, 12);
    if (detectionCron == null || detectionCron.isBlank()) {
      detectionCron = "0 5 * * * *";
    }
  }
}
