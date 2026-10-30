package io.opaa.audit;

import io.opaa.compliance.DataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bound of the anlassbezogene Klärung the operation sets, not the administration (ADR-0060,
 * Entscheidung 2): an auditor names the time range within it, never beyond.
 *
 * @param maxDays the longest time range one Klärung may name, in days, 92 when unset - as wide as
 *     one query of the revision. Must be positive: a Klärung without an upper bound would be the
 *     time series of one person the revision must not have.
 */
@ConfigurationProperties(prefix = "opaa.audit.incident-scope")
public record AuditIncidentScopeProperties(@DataImpact Integer maxDays) {

  public static final int DEFAULT_MAX_DAYS = 92;

  public AuditIncidentScopeProperties {
    if (maxDays == null) {
      maxDays = DEFAULT_MAX_DAYS;
    }
    if (maxDays <= 0) {
      throw new IllegalArgumentException(
          "opaa.audit.incident-scope.max-days must be positive - a Klärung without an upper bound"
              + " is a time series of one person");
    }
  }
}
