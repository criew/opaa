package io.opaa.permission;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Mindestgruppengröße (ADR-0036, "Zahlen, die dieser ADR setzt"): below it a group is, in a
 * unit of that size, a person with a name - so a group context below it discloses an individual
 * without the Schutzmechanik of the person context. The delivered value equals the enforced lower
 * bound, so it is <b>only raisable</b>: a house with small units raises it, nobody lowers it. A
 * start with a smaller value fails here rather than silently disabling the protection.
 */
@ConfigurationProperties(prefix = "opaa.permission")
public record GroupSizeProperties(Integer minimumGroupSize) {

  /** The value no configuration may go below, and at the same time the delivered default. */
  public static final int ENFORCED_MINIMUM = 5;

  public GroupSizeProperties {
    if (minimumGroupSize == null) {
      minimumGroupSize = ENFORCED_MINIMUM;
    }
    if (minimumGroupSize < ENFORCED_MINIMUM) {
      throw new IllegalArgumentException(
          "opaa.permission.minimum-group-size darf "
              + ENFORCED_MINIMUM
              + " nicht unterschreiten - der Wert ist nur nach oben änderbar (ADR-0036); "
              + "konfiguriert war "
              + minimumGroupSize);
    }
  }
}
