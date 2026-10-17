package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The deletion of the grants of the anlassbezogene Klärung against real rows, with a fixed clock:
 * an approved grant goes at the end of the calendar year after its {@code usable_until}, a request
 * never approved at the end of the year after its request time plus the usable window, and the
 * number of cases counted at approval stays behind.
 *
 * <p>The deletion pass is installation-wide and cannot be narrowed to this class's organization.
 * The rows here are therefore moved back to the years around 2000 and the fixed clocks stay before
 * 2005: a pass deletes nothing whose usability ended after its cutoff, so grants other classes
 * create at the real present are out of its reach.
 */
@OpaaIntegrationTest
class AuditIncidentScopeRetentionServiceIntegrationTest {

  private static final String REASON = "Verdacht auf unbefugten Zugriff";

  @Autowired private AuditIncidentScopeService incidentScopeService;
  @Autowired private AuditIncidentScopeGrantRepository grantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID requester;
  private UUID approver;
  private UUID subject;
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final Instant scopeStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private final Instant scopeEnd = scopeStart.plus(7, ChronoUnit.DAYS);

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Incident Scope Retention Org"))
            .getId();
    requester = createUser();
    approver = createUser();
    subject = createUser();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM audit_incident_scope_grants WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM audit_incident_scope_monthly_counts WHERE organization_id = ?",
        organizationId);
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void anApprovedGrantIsDeletedAtTheEndOfTheYearAfterItsUsabilityEnded() {
    UUID grant = approvedGrantUsableUntil(Instant.parse("2000-03-01T10:00:00Z"));

    runAt("2001-12-31T23:59:59Z", 1);
    assertThat(grantRepository.existsById(grant)).isTrue();

    runAt("2002-01-01T00:00:00Z", 1);
    assertThat(grantRepository.existsById(grant)).isFalse();
  }

  @Test
  void aRequestNeverApprovedCountsFromItsRequestTimePlusTheUsableWindow() {
    // usable at the latest until 14 January 2001, so it is kept through 2002
    UUID request = pendingRequestAt(Instant.parse("2000-12-15T10:00:00Z"));

    runAt("2002-01-01T00:00:00Z", 1);
    assertThat(grantRepository.existsById(request)).isTrue();

    runAt("2003-01-01T00:00:00Z", 1);
    assertThat(grantRepository.existsById(request)).isFalse();
  }

  @Test
  void aLongerConfiguredPeriodKeepsTheGrantForAsManyMoreYears() {
    UUID grant = approvedGrantUsableUntil(Instant.parse("2000-03-01T10:00:00Z"));

    runAt("2003-12-31T23:59:59Z", 3);
    assertThat(grantRepository.existsById(grant)).isTrue();

    runAt("2004-01-01T00:00:00Z", 3);
    assertThat(grantRepository.existsById(grant)).isFalse();
  }

  @Test
  void theCaseCountOutlivesTheDeletedGrant() {
    UUID grant = approvedGrantUsableUntil(Instant.parse("2000-03-01T10:00:00Z"));

    runAt("2002-01-01T00:00:00Z", 1);

    assertThat(grantRepository.existsById(grant)).isFalse();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT purpose || ':' || transition || ':' || case_count"
                    + " FROM audit_incident_scope_monthly_counts WHERE organization_id = ?",
                String.class,
                organizationId))
        .containsExactlyInAnyOrder(
            "SECURITY_INCIDENT:REQUESTED:1",
            "SECURITY_INCIDENT:APPROVED:1",
            "SECURITY_INCIDENT:EXPIRED:1");
  }

  private void runAt(String instant, int retentionYears) {
    new AuditIncidentScopeRetentionService(
            grantRepository,
            new AuditIncidentScopeRetention(retentionYears),
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
        .runOnce();
  }

  private UUID approvedGrantUsableUntil(Instant usableUntil) {
    UUID id = pendingRequestAt(usableUntil.minus(AuditIncidentScopeGrant.USABLE_WINDOW));
    incidentScopeService.approve(organizationId, id, approver);
    jdbcTemplate.update(
        "UPDATE audit_incident_scope_grants SET usable_until = ? WHERE id = ?",
        Timestamp.from(usableUntil),
        id);
    return id;
  }

  private UUID pendingRequestAt(Instant requestedAt) {
    UUID id =
        incidentScopeService
            .request(
                organizationId,
                requester,
                subject,
                scopeStart,
                scopeEnd,
                AuditIncidentScopePurpose.SECURITY_INCIDENT,
                REASON)
            .getId();
    jdbcTemplate.update(
        "UPDATE audit_incident_scope_grants SET requested_at = ? WHERE id = ?",
        Timestamp.from(requestedAt),
        id);
    return id;
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }
}
