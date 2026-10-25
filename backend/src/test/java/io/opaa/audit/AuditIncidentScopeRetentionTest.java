package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.common.ValidationException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The deletion period of the grants of the anlassbezogene Klärung: the end of the calendar year
 * that follows the end of usability by the configured number of years.
 */
class AuditIncidentScopeRetentionTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  @Test
  void theDeliveredPeriodIsOneYearAfterTheYearUsabilityEnded() {
    AuditIncidentScopeRetention retention = AuditIncidentScopeRetention.defaults();

    assertThat(retention.incidentScopeRetentionYears()).isEqualTo(1);
    // usable until some day in 2026: kept through 2027, due from the first moment of 2028
    assertThat(retention.deletionCutoff(Instant.parse("2027-12-31T23:59:59Z"), ZoneOffset.UTC))
        .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(retention.deletionCutoff(Instant.parse("2028-01-01T00:00:00Z"), ZoneOffset.UTC))
        .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
  }

  @Test
  void aLongerPeriodMovesTheCutoffBackByWholeYears() {
    AuditIncidentScopeRetention retention = new AuditIncidentScopeRetention(3);

    assertThat(retention.deletionCutoff(Instant.parse("2030-06-15T12:00:00Z"), ZoneOffset.UTC))
        .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
  }

  @Test
  void theYearTurnsInTheZoneOfTheServer() {
    AuditIncidentScopeRetention retention = AuditIncidentScopeRetention.defaults();

    // 23:30 UTC on 31 December is already the new year in Berlin
    assertThat(retention.deletionCutoff(Instant.parse("2027-12-31T23:30:00Z"), BERLIN))
        .isEqualTo(Instant.parse("2026-12-31T23:00:00Z"));
  }

  @Test
  void aPeriodOutsideItsBoundsIsRejected() {
    assertThatThrownBy(() -> new AuditIncidentScopeRetention(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incidentScopeRetentionYears");
    assertThatThrownBy(() -> new AuditIncidentScopeRetention(11))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new AuditIncidentScopeRetention(10).incidentScopeRetentionYears()).isEqualTo(10);
  }

  @Test
  void aRequestWhoseNamedOrRequestingPersonIsDeletedCannotBeApproved() {
    Instant now = Instant.parse("2026-10-15T10:00:00Z");
    AuditIncidentScopeGrant aboutNobody = grant(null, UUID.randomUUID());
    AuditIncidentScopeGrant byNobody = grant(UUID.randomUUID(), null);

    assertThatThrownBy(() -> aboutNobody.approve(UUID.randomUUID(), now))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("gelöscht");
    assertThatThrownBy(() -> byNobody.approve(UUID.randomUUID(), now))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("gelöscht");
  }

  @Test
  void anApprovalIsUsableForThirtyDaysFromTheGivenInstant() {
    Instant now = Instant.parse("2026-10-15T10:00:00Z");
    AuditIncidentScopeGrant grant = grant(UUID.randomUUID(), UUID.randomUUID());

    grant.approve(UUID.randomUUID(), now);

    assertThat(grant.getApprovedAt()).isEqualTo(now);
    assertThat(grant.getUsableUntil()).isEqualTo(Instant.parse("2026-11-14T10:00:00Z"));
  }

  private static AuditIncidentScopeGrant grant(UUID subject, UUID requester) {
    return new AuditIncidentScopeGrant(
        UUID.randomUUID(),
        subject,
        Instant.parse("2026-09-01T00:00:00Z"),
        Instant.parse("2026-09-30T00:00:00Z"),
        AuditIncidentScopePurpose.SECURITY_INCIDENT,
        "Verdacht auf unbefugten Zugriff",
        requester);
  }
}
