package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied (#288).
 * Proves the two #391 acceptance criteria that concern the pseudonym mapping: it is stable per user
 * (not re-minted per event), and deleting it - which happens automatically when the account itself
 * is deleted, via {@code fk_audit_actor_pseudonyms_user}'s {@code ON DELETE CASCADE} - leaves an
 * already-written {@link AuditLogEntry} unchanged
 * (docs/features/security-and-compliance.md#unveränderlichkeit-und-löschrecht).
 */
@OpaaIntegrationTest
class AuditActorPseudonymServiceIntegrationTest {

  @Autowired private AuditActorPseudonymService pseudonymService;
  @Autowired private AuditActorPseudonymRepository pseudonymRepository;
  @Autowired private AuditLogService auditLogService;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Pseudonym Test Org"))
            .getId();
  }

  @AfterEach
  void tearDown() {
    // See AuditLogServiceIntegrationTest#tearDown for why this goes through JdbcTemplate (not
    // auditLogRepository, whose delete/deleteAll is a no-op by design) and for why this cleanup
    // succeeds here (the test datasource's superuser account) but would not for the real,
    // restricted application account.
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteAll(
        userRepository.findAll().stream()
            .filter(user -> organizationId.equals(user.getOrganizationId()))
            .toList());
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void thePseudonymIsStablePerUserNotReMintedPerCall() {
    UUID userId = createUser();

    UUID first = pseudonymService.pseudonymFor(userId, organizationId);
    UUID second = pseudonymService.pseudonymFor(userId, organizationId);

    assertThat(second).isEqualTo(first);
  }

  @Test
  void deletingTheAccountRemovesThePseudonymMappingButLeavesTheAuditEntryUnchanged() {
    UUID userId = createUser();
    UUID pseudonymId = pseudonymService.pseudonymFor(userId, organizationId);

    AuditLogEntry saved =
        auditLogService.record(
            AuditLogEntry.withoutSubject(
                organizationId,
                ActorKind.USER,
                pseudonymId.toString(),
                AuditEventType.SPACE_CREATED,
                io.opaa.audit.AuditObjectType.SPACE,
                "space-1",
                "Space 1",
                null,
                null,
                AuditOutcome.SUCCESS,
                null,
                null));

    userRepository.deleteById(userId);

    assertThat(pseudonymRepository.findById(pseudonymId)).isEmpty();
    AuditLogEntry reread = auditLogRepository.findById(saved.getEventId()).orElseThrow();
    assertThat(reread.getActorRef()).isEqualTo(pseudonymId.toString());
    assertThat(reread.getEventType()).isEqualTo(AuditEventType.SPACE_CREATED);
  }

  private UUID createUser() {
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }
}
