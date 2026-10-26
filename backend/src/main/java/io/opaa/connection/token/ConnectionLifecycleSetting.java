package io.opaa.connection.token;

/**
 * The thresholds and periods of the connections the system administration sets (ADR-0041,
 * Entscheidung 7; ADR-0060, Entscheidung 1). The factory default applies while the database holds
 * no value; the bounds are fixed here and repeated as {@code CHECK} in the table, so neither the
 * administration nor SQL stretches them. The name is the row key in {@code
 * connection_lifecycle_settings} and never changes.
 */
public enum ConnectionLifecycleSetting {
  /** Days without a sign-in after which a person's connections rest. */
  INACTIVITY_THRESHOLD_DAYS(90, 30, 365),
  /** Days the private libraries of a deactivated person are kept before they are erased. */
  PRIVATE_LIBRARY_DELETION_DAYS(30, 1, 90),
  /**
   * Expired connected accounts on one profile from which the overview of the administration warns.
   */
  EXPIRED_CONNECTION_WARNING_THRESHOLD(10, 1, 1000);

  private final int factoryDefault;
  private final int min;
  private final int max;

  ConnectionLifecycleSetting(int factoryDefault, int min, int max) {
    this.factoryDefault = factoryDefault;
    this.min = min;
    this.max = max;
  }

  public int factoryDefault() {
    return factoryDefault;
  }

  public int min() {
    return min;
  }

  public int max() {
    return max;
  }

  /** Whether {@code value} lies within the bounds, both included. */
  public boolean admits(int value) {
    return value >= min && value <= max;
  }
}
