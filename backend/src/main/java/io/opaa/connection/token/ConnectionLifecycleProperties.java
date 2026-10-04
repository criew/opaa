package io.opaa.connection.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The installation's setting of the connections' lifecycle (ADR-0041, Entscheidung 7): without a
 * sign-in for {@code inactivityThresholdDays} a person's connections rest. The bounds are fixed in
 * code, so no configuration and no SQL can stretch them; a start with a value outside them fails.
 */
@ConfigurationProperties(prefix = "opaa.connection")
public record ConnectionLifecycleProperties(Integer inactivityThresholdDays) {

  public static final int DEFAULT_INACTIVITY_DAYS = 90;
  public static final int MIN_INACTIVITY_DAYS = 30;
  public static final int MAX_INACTIVITY_DAYS = 365;

  public ConnectionLifecycleProperties {
    if (inactivityThresholdDays == null) {
      inactivityThresholdDays = DEFAULT_INACTIVITY_DAYS;
    }
    if (inactivityThresholdDays < MIN_INACTIVITY_DAYS
        || inactivityThresholdDays > MAX_INACTIVITY_DAYS) {
      throw new IllegalArgumentException(
          "opaa.connection.inactivity-threshold-days muss zwischen "
              + MIN_INACTIVITY_DAYS
              + " und "
              + MAX_INACTIVITY_DAYS
              + " Tagen liegen (ADR-0041); konfiguriert war "
              + inactivityThresholdDays);
    }
  }

  /** The delivered setting, for tests and code outside a Spring context. */
  public static ConnectionLifecycleProperties defaults() {
    return new ConnectionLifecycleProperties(null);
  }

  public Duration inactivityThreshold() {
    return Duration.ofDays(inactivityThresholdDays);
  }
}
