package io.opaa.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * How long a grant of the anlassbezogene Klärung is kept: until the end of the calendar year that
 * lies {@code incidentScopeRetentionYears} after the year its usability ended - for an approved
 * grant {@code usable_until}, for a request never approved its request time plus {@link
 * AuditIncidentScopeGrant#USABLE_WINDOW}. The factory default of one year follows § 76 Abs. 4 BDSG;
 * a Dienstvereinbarung can lengthen it within the bounds fixed here ({@link
 * AuditIncidentScopeRetentionSettings}).
 *
 * @param incidentScopeRetentionYears calendar years after the end of a grant's usability until it
 *     is deleted at the end of that year, between 1 and 10
 */
public record AuditIncidentScopeRetention(int incidentScopeRetentionYears) {

  public static final int DEFAULT_YEARS = 1;
  public static final int MIN_YEARS = 1;
  public static final int MAX_YEARS = 10;

  public AuditIncidentScopeRetention {
    if (incidentScopeRetentionYears < MIN_YEARS || incidentScopeRetentionYears > MAX_YEARS) {
      throw new IllegalArgumentException(
          "incidentScopeRetentionYears must lie between "
              + MIN_YEARS
              + " and "
              + MAX_YEARS
              + ", got "
              + incidentScopeRetentionYears);
    }
  }

  /** The factory default, in force while the administration set none. */
  public static AuditIncidentScopeRetention defaults() {
    return new AuditIncidentScopeRetention(DEFAULT_YEARS);
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
