package io.opaa.succession;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two numbers the lifecycle knows (ADR-0036, Entscheidung 6).
 *
 * @param agingThresholdMonths from which age an entry of the operational list is highlighted, and
 *     for how long a Sichtungsvermerk lifts that highlight again. Default 12 months, taken from the
 *     maximum validity of the Vollmacht; it highlights, it never triggers anything
 * @param detectionCron when the detection run looks - hourly by default. It writes only the
 *     timestamps: without a named, regular run "age" would mean "since somebody last looked"
 */
@ConfigurationProperties(prefix = "opaa.succession")
public record SuccessionProperties(int agingThresholdMonths, String detectionCron) {

  public SuccessionProperties {
    if (agingThresholdMonths <= 0) {
      agingThresholdMonths = 12;
    }
    if (detectionCron == null || detectionCron.isBlank()) {
      detectionCron = "0 5 * * * *";
    }
  }
}
