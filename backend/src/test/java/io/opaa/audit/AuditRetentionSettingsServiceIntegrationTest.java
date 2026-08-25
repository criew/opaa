package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #395: {@link AuditRetentionSettingsService} against a real Postgres database with the real,
 * versioned Liquibase schema applied (migration 023). Proves the acceptance criteria a plain unit
 * test against a mocked repository could not: the database's own {@code
 * chk_audit_retention_settings_months} bound actually rejects a write outside 1-10 years (not just
 * this service's own pre-check), a retention change writes exactly one {@code
 * AUDIT_LOG_CONFIGURATION_CHANGED} audit entry (never fails silently, never writes more than one).
 */
@OpaaIntegrationTest
class AuditRetentionSettingsServiceIntegrationTest {

  @Autowired private AuditRetentionSettingsService retentionSettingsService;
  @Autowired private AuditRetentionSettingsRepository retentionSettingsRepository;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Retention Test Org"))
            .getId();
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "retention@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "UPDATE audit_retention_settings SET retention_months = 36, updated_at = now()"
            + " WHERE id = 1");
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void aValidRetentionChangeIsAppliedAndAuditedExactlyOnce() {
    int before = retentionSettingsService.currentRetentionMonths();

    AuditRetentionUpdateResult result =
        retentionSettingsService.updateRetention(organizationId, userId, 60, "Testumstellung");

    assertThat(result.retentionMonths()).isEqualTo(60);
    assertThat(retentionSettingsService.currentRetentionMonths()).isEqualTo(60);
    assertThat(before).isNotEqualTo(60);

    long changeEntries =
        auditLogRepository
            .findByOrganizationIdAndEventTypeAndRecordedAtBetween(
                organizationId,
                AuditEventType.AUDIT_LOG_CONFIGURATION_CHANGED,
                java.time.Instant.now().minusSeconds(60),
                java.time.Instant.now().plusSeconds(60),
                org.springframework.data.domain.PageRequest.of(0, 50))
            .stream()
            .filter(entry -> "Testumstellung".equals(entry.getReason()))
            .count();
    assertThat(changeEntries).isEqualTo(1);
  }

  @Test
  void aRetentionBelowOneYearIsRejectedBeforeAnyWrite() {
    int before = retentionSettingsService.currentRetentionMonths();

    assertThatThrownBy(
            () -> retentionSettingsService.updateRetention(organizationId, userId, 11, null))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(retentionSettingsService.currentRetentionMonths()).isEqualTo(before);
  }

  @Test
  void aRetentionAboveTenYearsIsRejectedBeforeAnyWrite() {
    int before = retentionSettingsService.currentRetentionMonths();

    assertThatThrownBy(
            () -> retentionSettingsService.updateRetention(organizationId, userId, 121, null))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(retentionSettingsService.currentRetentionMonths()).isEqualTo(before);
  }
}
