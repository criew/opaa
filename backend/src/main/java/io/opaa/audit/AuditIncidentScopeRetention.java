package io.opaa.audit;

import io.opaa.compliance.DataImpact;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a grant of the anlassbezogene Klärung is kept: until the end of the calendar year that
 * lies {@code incidentScopeRetentionYears} after the year its usability ended - for an approved
 * grant {@code usable_until}, for a request never approved its request time plus {@link
 * AuditIncidentScopeGrant#USABLE_WINDOW}. The default of one year follows § 76 Abs. 4 BDSG; a
 * Dienstvereinbarung can lengthen it within the bounds fixed here, a start outside them fails.
 *
 * @param incidentScopeRetentionYears calendar years after the end of a grant's usability until it
 *     is deleted at the end of that year; default 1, between 1 and 10
 */
@ConfigurationProperties(prefix = "opaa.audit")
public record AuditIncidentScopeRetention(@DataImpact Integer incidentScopeRetentionYears) {

  public static final int DEFAULT_YEARS = 1;
  public static final int MIN_YEARS = 1;
  public static final int MAX_YEARS = 10;

  public AuditIncidentScopeRetention {
    if (incidentScopeRetentionYears == null) {
      incidentScopeRetentionYears = DEFAULT_YEARS;
    }
    if (incidentScopeRetentionYears < MIN_YEARS || incidentScopeRetentionYears > MAX_YEARS) {
      throw new IllegalArgumentException(
          "opaa.audit.incident-scope-retention-years must lie between "
              + MIN_YEARS
              + " and "
              + MAX_YEARS
              + ", got "
              + incidentScopeRetentionYears);
    }
  }

  /** The delivered setting, for tests and code outside a Spring context. */
  public static AuditIncidentScopeRetention defaults() {
    return new AuditIncidentScopeRetention(null);
  }

  /**
   * A grant whose usability ended before this instant is due for deletion at {@code now}: the start
   * of the calendar year {@code incidentScopeRetentionYears} before the current one, in {@code
   * zone}.
   */
  public Instant deletionCutoff(Instant now, ZoneId zone) {
    int currentYear = LocalDate.ofInstant(now, zone).getYear();
    return LocalDate.of(currentYear - incidentScopeRetentionYears, 1, 1)
        .atStartOfDay(zone)
        .toInstant();
  }
}
