package io.opaa.audit;

import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Counts of the anlassbezogenen Klärungen of a reporting period, by purpose. */
@Service
public class AuditIncidentScopeCaseReport {

  private final AuditIncidentScopeGrantRepository repository;
  private final Clock clock;

  AuditIncidentScopeCaseReport(AuditIncidentScopeGrantRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public IncidentScopeCases report(UUID organizationId, ReportingPeriod period) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
