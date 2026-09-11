package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.MailEncryption;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #1536, ADR-0033 Entscheidung 10: {@link MailSettingsService} against a real Postgres with the
 * versioned Liquibase schema (changeset 011). Covers what a mocked repository could not - that the
 * password really is only ever in the database as ciphertext, that the three-way {@code ***} rule
 * behaves, that the audit entry carries no password value, and that the snapshot every send reads
 * follows a committed change without a restart.
 *
 * <p>Carries the canonical {@link OpaaIntegrationTest} signature (AGENTS.md,
 * "Spring-Testkontexte"), so it shares one context and one container with the other classes on that
 * meta-annotation.
 */
@OpaaIntegrationTest
class MailSettingsServiceIntegrationTest {

  @Autowired private MailSettingsService mailSettingsService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Mail Test Org")).getId();
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "mail@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();
  }

  @AfterEach
  void tearDown() {
    // Through the service, not through JDBC: this also rebuilds the process-local snapshot, which a
    // raw UPDATE would leave pointing at the settings of the test that just ran.
    mailSettingsService.updateSettings(organizationId, userId, disabled());
    jdbcTemplate.update(
        "UPDATE mail_settings SET last_success_at = NULL, last_failure_at = NULL,"
            + " last_failure_reason = NULL WHERE id = 1");
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void anUnconfiguredDeploymentIsNotSendableAndHasNoStatusYet() {
    assertThat(mailSettingsService.snapshot().sendable()).isFalse();
    assertThat(mailSettingsService.status().lastAttemptFailed()).isFalse();
    assertThat(mailSettingsService.currentSettings().getPasswordCiphertext()).isNull();
  }

  @Test
  void storesThePasswordOnlyAsCiphertextAndNeverInClear() {
    mailSettingsService.updateSettings(organizationId, userId, configured("geheimesKennwort"));

    String stored =
        jdbcTemplate.queryForObject(
            "SELECT password_ciphertext FROM mail_settings WHERE id = 1", String.class);
    assertThat(stored).isNotNull().startsWith("enc:v1:").doesNotContain("geheimesKennwort");
    assertThat(mailSettingsService.snapshot().password()).isEqualTo("geheimesKennwort");
  }

  @Test
  void theMaskLeavesTheStoredPasswordUntouched() {
    mailSettingsService.updateSettings(organizationId, userId, configured("geheimesKennwort"));
    String before = storedCiphertext();

    mailSettingsService.updateSettings(
        organizationId, userId, configured(MailSettingsService.PASSWORD_MASK));

    assertThat(storedCiphertext()).isEqualTo(before);
    assertThat(mailSettingsService.snapshot().password()).isEqualTo("geheimesKennwort");
  }

  @Test
  void anOmittedPasswordAlsoLeavesTheStoredOneUntouched() {
    mailSettingsService.updateSettings(organizationId, userId, configured("geheimesKennwort"));
    String before = storedCiphertext();

    mailSettingsService.updateSettings(organizationId, userId, configured(null));

    assertThat(storedCiphertext()).isEqualTo(before);
  }

  @Test
  void anEmptyStringClearsTheStoredPassword() {
    mailSettingsService.updateSettings(organizationId, userId, configured("geheimesKennwort"));

    mailSettingsService.updateSettings(organizationId, userId, configured(""));

    assertThat(storedCiphertext()).isNull();
    assertThat(mailSettingsService.snapshot().password()).isNull();
  }

  @Test
  void aCommittedChangeReachesTheSnapshotWithoutARestart() {
    assertThat(mailSettingsService.snapshot().sendable()).isFalse();

    mailSettingsService.updateSettings(organizationId, userId, configured("geheimesKennwort"));

    MailSettingsSnapshot snapshot = mailSettingsService.snapshot();
    assertThat(snapshot.sendable()).isTrue();
    assertThat(snapshot.host()).isEqualTo("smtp.intern.example");
    assertThat(snapshot.port()).isEqualTo(2525);
    assertThat(snapshot.encryption()).isEqualTo(MailEncryption.NONE);
  }

  @Test
  void theSnapshotNeverPrintsThePasswordEvenWhenItIsLoggedOrShownInADebugger() {
    mailSettingsService.updateSettings(organizationId, userId, configured("geheimesKennwort"));

    assertThat(mailSettingsService.snapshot().toString())
        .doesNotContain("geheimesKennwort")
        .contains("password=***");
  }

  @Test
  void aChangeIsAuditedOnceAndRecordsOnlyWhetherAPasswordIsSet() {
    mailSettingsService.updateSettings(organizationId, userId, configured("geheimesKennwort"));

    List<Map<String, Object>> entries =
        jdbcTemplate.queryForList(
            "SELECT event_type, object_type, object_label, before, after FROM audit_log"
                + " WHERE organization_id = ? AND event_type = ?",
            organizationId,
            AuditEventType.MAIL_SETTINGS_CHANGED.name());

    assertThat(entries).hasSize(1);
    Map<String, Object> entry = entries.getFirst();
    assertThat(entry.get("object_type")).isEqualTo(AuditObjectType.SYSTEM_SETTING.name());
    assertThat(entry.get("object_label")).isEqualTo("E-Mail-Versand");
    assertThat((String) entry.get("before")).contains("\"passwordSet\":false");
    assertThat((String) entry.get("after"))
        .contains("\"passwordSet\":true")
        .contains("smtp.intern.example")
        .doesNotContain("geheimesKennwort");
  }

  @Test
  void refusesToSwitchSendingOnWithoutHostPortOrSenderAddress() {
    assertThatThrownBy(
            () ->
                mailSettingsService.updateSettings(
                    organizationId,
                    userId,
                    new MailSettingsUpdate(
                        true,
                        null,
                        2525,
                        null,
                        null,
                        MailEncryption.NONE,
                        "opaa@intern.example",
                        null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("SMTP-Server");

    assertThatThrownBy(
            () ->
                mailSettingsService.updateSettings(
                    organizationId,
                    userId,
                    new MailSettingsUpdate(
                        true,
                        "smtp.intern.example",
                        null,
                        null,
                        null,
                        MailEncryption.NONE,
                        "opaa@intern.example",
                        null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Port");

    assertThatThrownBy(
            () ->
                mailSettingsService.updateSettings(
                    organizationId,
                    userId,
                    new MailSettingsUpdate(
                        true,
                        "smtp.intern.example",
                        2525,
                        null,
                        null,
                        MailEncryption.NONE,
                        null,
                        null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Absenderadresse");
  }

  @Test
  void refusesASenderAddressThatIsNotAnAddressAndAPortOutsideTheValidRange() {
    assertThatThrownBy(
            () ->
                mailSettingsService.updateSettings(
                    organizationId,
                    userId,
                    new MailSettingsUpdate(
                        false,
                        "smtp.intern.example",
                        2525,
                        null,
                        null,
                        MailEncryption.NONE,
                        "kein-postfach",
                        null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Absenderadresse");

    assertThatThrownBy(
            () ->
                mailSettingsService.updateSettings(
                    organizationId,
                    userId,
                    new MailSettingsUpdate(
                        false,
                        "smtp.intern.example",
                        70000,
                        null,
                        null,
                        MailEncryption.NONE,
                        null,
                        null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Port");
  }

  @Test
  void writesTheSendStatusToTheRowAndToTheInMemoryStatus() {
    Instant failedAt = Instant.parse("2026-09-11T08:00:00Z");
    Instant succeededAt = Instant.parse("2026-09-11T09:00:00Z");

    mailSettingsService.recordSendOutcome(failedAt, "Connection refused");
    assertThat(mailSettingsService.status().lastAttemptFailed()).isTrue();
    assertThat(mailSettingsService.status().lastFailureReason()).isEqualTo("Connection refused");

    mailSettingsService.recordSendOutcome(succeededAt, null);
    MailSendStatus status = mailSettingsService.status();
    assertThat(status.lastAttemptFailed()).isFalse();
    // The older failure is kept: "zuletzt erfolgreich" and "zuletzt fehlgeschlagen" answer
    // different questions, and clearing one would hide intermittent failure.
    assertThat(status.lastFailureAt()).isEqualTo(failedAt);
    assertThat(status.lastSuccessAt()).isEqualTo(succeededAt);
    assertThat(mailSettingsService.currentSettings().getLastFailureReason())
        .isEqualTo("Connection refused");
  }

  @Test
  void truncatesAnOverlongFailureCauseToWhatTheColumnHolds() {
    mailSettingsService.recordSendOutcome(Instant.now(), "x".repeat(900));

    assertThat(mailSettingsService.currentSettings().getLastFailureReason()).hasSize(500);
  }

  private String storedCiphertext() {
    return jdbcTemplate.queryForObject(
        "SELECT password_ciphertext FROM mail_settings WHERE id = 1", String.class);
  }

  private static MailSettingsUpdate configured(String password) {
    return new MailSettingsUpdate(
        true,
        "smtp.intern.example",
        2525,
        "kennung",
        password,
        MailEncryption.NONE,
        "opaa@intern.example",
        "OPAA");
  }

  private static MailSettingsUpdate disabled() {
    return new MailSettingsUpdate(false, null, null, null, "", MailEncryption.STARTTLS, null, null);
  }
}
