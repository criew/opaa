package io.opaa.connection.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the private libraries of a deactivated person are kept before they are erased (ADR-0041,
 * Entscheidung 7). The bounds are fixed in code, so no configuration and no SQL can stretch them; a
 * start with a value outside them fails.
 */
@ConfigurationProperties(prefix = "opaa.connection")
public record PrivateLibraryDeletionPeriod(Integer privateLibraryDeletionDays) {

  public static final int DEFAULT_DAYS = 30;
  public static final int MIN_DAYS = 1;
  public static final int MAX_DAYS = 90;

  public PrivateLibraryDeletionPeriod {
    if (privateLibraryDeletionDays == null) {
      privateLibraryDeletionDays = DEFAULT_DAYS;
    }
    if (privateLibraryDeletionDays < MIN_DAYS || privateLibraryDeletionDays > MAX_DAYS) {
      throw new IllegalArgumentException(
          "opaa.connection.private-library-deletion-days muss zwischen "
              + MIN_DAYS
              + " und "
              + MAX_DAYS
              + " Tagen liegen (ADR-0041); konfiguriert war "
              + privateLibraryDeletionDays);
    }
  }

  /** The delivered setting, for tests and code outside a Spring context. */
  public static PrivateLibraryDeletionPeriod defaults() {
    return new PrivateLibraryDeletionPeriod(null);
  }

  public Duration period() {
    return Duration.ofDays(privateLibraryDeletionDays);
  }
}
