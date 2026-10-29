package io.opaa.succession;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

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

  /** The schedule of an unset value: five minutes past every hour. */
  public static final String DEFAULT_DETECTION_CRON = "0 5 * * * *";

  /**
   * An unset value takes {@link #DEFAULT_DETECTION_CRON}; a blank or malformed one refuses the
   * start, since the run cannot be switched off and a set value is meant to be used.
   */
  public SuccessionProperties {
    if (detectionCron == null) {
      detectionCron = DEFAULT_DETECTION_CRON;
    } else if (detectionCron.isBlank()) {
      throw new IllegalArgumentException(
          "OPAA_SUCCESSION_DETECTION_CRON (opaa.succession.detection-cron) is set but empty;"
              + " remove it to run the detection hourly, or set a cron expression with six fields"
              + " such as \""
              + DEFAULT_DETECTION_CRON
              + "\"");
    } else if (!CronExpression.isValidExpression(detectionCron)) {
      throw new IllegalArgumentException(
          "OPAA_SUCCESSION_DETECTION_CRON (opaa.succession.detection-cron) is \""
              + detectionCron
              + "\", which is no cron expression with six fields such as \""
              + DEFAULT_DETECTION_CRON
              + "\"");
    }
  }
}
