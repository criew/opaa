package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The leitplanken against the real, Liquibase-built schema rather than a mock: the database itself
 * refuses an unbefristetes, bereichsloses Dauerrecht, a freshly created library really is locked,
 * and the seeded retention really is twelve months.
 */
@OpaaIntegrationTest
class DiagnosticAccessIntegrationTest {

  @Autowired private DiagnosticImpersonationGrantService grantService;
  @Autowired private DiagnosticImpersonationGrantRepository grantRepository;
  @Autowired private DiagnosticContextRetentionSettingsRepository retentionRepository;
  @Autowired private DiagnosticContextRetentionDeletionService deletionService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private CurrentUser admin;
  private UUID holderId;
  private UUID scopeGroupId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Diagnostic Access Org"))
            .getId();
    holderId = persistUser("holder").getId();
    UUID adminId = persistUser("admin").getId();
    admin = CurrentUser.of(adminId, organizationId, SystemRole.SYSTEM_ADMIN, "Admin");
    scopeGroupId =
        groupRepository
            .save(
                new Group(organizationId, GroupKind.ORG_UNIT, "Amt für Personal", null, null, null))
            .getId();
  }

  @Test
  void theDatabaseRefusesAnUnboundedPermanentRight() {
    Instant from = Instant.now();
    DiagnosticImpersonationGrant tooLong =
        new DiagnosticImpersonationGrant(
            organizationId,
            holderId,
            scopeGroupId,
            from,
            from.plus(400, ChronoUnit.DAYS),
            admin.id(),
            from);

    assertThatThrownBy(() -> grantRepository.saveAndFlush(tooLong))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aSystemAdminHoldsNoBefugnisJustByBeingOne() {
    UUID targetId = persistUser("target").getId();

    assertThatThrownBy(() -> grantService.requireImpersonationPermission(admin, targetId))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aGrantedBefugnisIsStoredWithItsScopeAndWindow() {
    Instant from = Instant.now();
    DiagnosticImpersonationGrant grant =
        grantService.grant(
            admin,
            new DiagnosticImpersonationGrantCreation(
                holderId, scopeGroupId, from, from.plus(30, ChronoUnit.DAYS)));

    assertThat(grantRepository.findByIdAndOrganizationId(grant.getId(), organizationId))
        .isPresent()
        .get()
        .satisfies(
            stored -> {
              assertThat(stored.getScopeGroupId()).isEqualTo(scopeGroupId);
              assertThat(stored.isActiveAt(from.plus(1, ChronoUnit.DAYS))).isTrue();
              assertThat(stored.isActiveAt(from.plus(31, ChronoUnit.DAYS))).isFalse();
            });
  }

  @Test
  void aFreshlyCreatedLibraryIsDiagnosegesperrt() {
    KnowledgeLibrary saved =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                organizationId,
                "Personalvorgänge",
                null,
                holderId,
                LibraryVisibility.PRIVATE,
                false));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT diagnostics_locked FROM knowledge_libraries WHERE id = ?",
                Boolean.class,
                saved.getId()))
        .isTrue();
  }

  @Test
  void theProtocolIsKeptForTwelveMonthsByDefaultAndTheDeletionRunsWithoutConfiguration() {
    assertThat(retentionRepository.findSingleton())
        .isPresent()
        .get()
        .extracting(DiagnosticContextRetentionSettings::getRetentionMonths)
        .isEqualTo(12);

    assertThat(deletionService.runOnce()).isEmpty();
  }

  @Test
  void aProtocolEntryCarriesTheMandatoryFieldsOfLeitplankeF() {
    DiagnosticContextLogEntry entry =
        new DiagnosticContextLogEntry(
            organizationId,
            UUID.randomUUID().toString(),
            DiagnosticTargetKind.USER,
            UUID.randomUUID().toString(),
            "Wo steht die Dienstanweisung?",
            1,
            "chunk-1",
            "libraries=[];lockedLibraries=[]",
            "Beschwerde 4711");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM diagnostic_context_log WHERE event_id = ?",
                Integer.class,
                saveEntry(entry)))
        .isEqualTo(1);
  }

  private UUID saveEntry(DiagnosticContextLogEntry entry) {
    jdbcTemplate.update(
        "INSERT INTO diagnostic_context_log (event_id, recorded_at, organization_id, actor_ref,"
            + " target_kind, target_ref, test_question, hit_count, hit_refs, permission_snapshot,"
            + " justification) VALUES (?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        entry.getEventId(),
        entry.getOrganizationId(),
        entry.getActorRef(),
        entry.getTargetKind().name(),
        entry.getTargetRef(),
        entry.getTestQuestion(),
        entry.getHitCount(),
        entry.getHitRefs(),
        entry.getPermissionSnapshot(),
        entry.getJustification());
    return entry.getEventId();
  }

  private User persistUser(String subject) {
    User user = new User(subject + "-" + UUID.randomUUID(), "test-issuer", null, subject);
    user.setOrganizationId(organizationId);
    return userRepository.save(user);
  }
}
