package io.opaa.connection.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * From how many expired connected accounts on one profile the administration's overview warns. The
 * warning follows the masked numbers only ({@link PersonNumbers}), so below the minimum group size
 * it never shows.
 *
 * @param warningThreshold expired connections per profile (default 10, at least 1)
 */
@ConfigurationProperties(prefix = "opaa.connection.expired-connections")
public record ExpiredConnectionWarningProperties(Integer warningThreshold) {

  public static final int DEFAULT_THRESHOLD = 10;

  public ExpiredConnectionWarningProperties {
    warningThreshold = warningThreshold == null ? DEFAULT_THRESHOLD : warningThreshold;
    if (warningThreshold < 1) {
      throw new IllegalArgumentException(
          "opaa.connection.expired-connections.warning-threshold must be at least 1");
    }
  }

  /** The delivered setting, for tests and code outside a Spring context. */
  public static ExpiredConnectionWarningProperties defaults() {
    return new ExpiredConnectionWarningProperties(null);
  }
}
