package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetGrantService;
import io.opaa.library.AssetGrantUpsert;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
  @Autowired private DiagnosticContextLogRepository logRepository;
  @Autowired private DiagnosticContextLogQueryService logQueryService;
  @Autowired private ForeignDiagnosticContextService foreignDiagnosticContextService;
  @Autowired private LibraryDiagnosticsLockService lockService;
  @Autowired private AssetGrantService assetGrantService;
  @Autowired private AssetGrantRepository assetGrantRepository;
  @Autowired private TransactionTemplate transactionTemplate;
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

  /**
   * The write path of Leitplanke (f) through the repository the application actually uses, not
   * through a hand-written INSERT: this is what proves the entity mapping, {@code
   * Persistable#isNew()} and the {@code @PrePersist} timestamp work against the real, partitioned
   * table - a row inserted by the test's own SQL would prove only that SQL.
   */
  @Test
  void aProtocolEntryCarriesTheMandatoryFieldsOfLeitplankeF() {
    DiagnosticContextLogEntry entry =
        logRepository.save(
            new DiagnosticContextLogEntry(
                organizationId,
                UUID.randomUUID().toString(),
                DiagnosticTargetKind.USER,
                UUID.randomUUID().toString(),
                "Wo steht die Dienstanweisung?",
                1,
                "chunk-1",
                "libraries=[];lockedLibraries=[]",
                "Beschwerde 4711"));

    Map<String, Object> stored =
        jdbcTemplate.queryForMap(
            "SELECT * FROM diagnostic_context_log WHERE event_id = ?", entry.getEventId());
    assertThat(stored)
        .containsEntry("organization_id", organizationId)
        .containsEntry("actor_ref", entry.getActorRef())
        .containsEntry("target_kind", "USER")
        .containsEntry("target_ref", entry.getTargetRef())
        .containsEntry("test_question", "Wo steht die Dienstanweisung?")
        .containsEntry("hit_count", 1)
        .containsEntry("hit_refs", "chunk-1")
        .containsEntry("permission_snapshot", "libraries=[];lockedLibraries=[]")
        .containsEntry("justification", "Beschwerde 4711");
    assertThat(stored.get("recorded_at")).isNotNull();
  }

  /**
   * Leitplanke (h): the rejected access to the Gesamtprotokoll must still be readable afterwards.
   * The call runs inside a transaction that the rejection rolls back - an entry written by a
   * transaction-joining recorder disappears with it, which is what this asserts against.
   */
  @Test
  void aRejectedGesamtprotokollAccessSurvivesTheRollbackOfTheRejectedCall() {
    Instant from = Instant.now().minus(1, ChronoUnit.DAYS);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    status ->
                        logQueryService.findByTimeRange(
                            admin, from, Instant.now(), "Beschwerde 4711", 0, 50)))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ? AND event_type ="
                    + " 'AUDIT_LOG_ACCESSED' AND outcome = 'DENIED' AND reason = ?",
                Integer.class,
                organizationId,
                "Beschwerde 4711"))
        .isEqualTo(1);
  }

  /**
   * Regression guard for #1256: an over-length reason must not reach {@code
   * DiagnosticContextLogWriter}'s underlying {@code varchar(1000)} column unrejected - previously
   * that write itself failed, surfacing as a 500 with no protocol entry at all. Against the real
   * schema so the bound the service checks and the column's actual bound cannot drift apart.
   */
  @Test
  void anOverlongReasonToTheGesamtprotokollIsRejectedAndTheAttemptIsRecorded() {
    User auditorUser = persistUser("auditor");
    auditorUser.setSystemRole(SystemRole.AUDITOR);
    userRepository.save(auditorUser);
    CurrentUser auditor =
        CurrentUser.of(auditorUser.getId(), organizationId, SystemRole.AUDITOR, "Auditorin");
    Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
    String overlong = "x".repeat(1001);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    status ->
                        logQueryService.findByTimeRange(
                            auditor, from, Instant.now(), overlong, 0, 50)))
        .isInstanceOf(ValidationException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ? AND event_type ="
                    + " 'AUDIT_LOG_ACCESSED' AND outcome = 'DENIED' AND length(reason) = 1000",
                Integer.class,
                organizationId))
        .isEqualTo(1);
  }

  /**
   * Regression guard for #1256: {@code LibraryAccessService#readableLibraryIds} carries no
   * system-admin bypass by design, so without one in {@code
   * ForeignDiagnosticContextService#executeForProfile} a SYSTEM_ADMIN caller was refused the entire
   * profile diagnosis for any library they administer but hold no grant on. Against real grants and
   * groups so the bypass is proven against the actual containment check, not a mocked one.
   */
  @Test
  void aSystemAdminRunsAProfileDiagnosisEvenOverALibraryTheyCannotReadThemselves() {
    KnowledgeLibrary library = persistLibraryOwnedBy(holderId);
    CurrentUser owner = CurrentUser.of(holderId, organizationId, SystemRole.USER, "Zustaendige");
    lockService.setLocked(owner, library.getId(), false);
    Group profile =
        groupRepository.save(
            new Group(organizationId, GroupKind.AD_HOC, "Sachbearbeitung", null, null, null));
    assetGrantService.upsertGrant(
        library.getId(),
        new AssetGrantUpsert(PermissionSubjectType.GROUP, profile.getId(), AssetRole.VIEWER),
        owner);

    ForeignDiagnosticOutcome<String> outcome =
        foreignDiagnosticContextService.execute(
            admin,
            ForeignDiagnosticRequest.forProfile(profile.getId(), "Wo steht das?"),
            context -> new ForeignDiagnosticFindings<>(List.of(), "Anzeige"));

    assertThat(outcome.context().searchableLibraryIds()).contains(library.getId());
  }

  /**
   * Leitplanke (e) against the real grant model: the two-step path, in which an administrator first
   * grants themselves {@code OWNER} through the administrative floor of the grant endpoint and then
   * lifts the lock as "the responsible body". The self-grant itself succeeds - granting is the
   * administration's job - and the lock holds anyway.
   */
  @Test
  void anAdministratorWhoGrantsThemselvesOwnerStillCannotLiftAForeignLock() {
    KnowledgeLibrary library = persistLibraryOwnedBy(holderId);

    assetGrantService.upsertGrant(
        library.getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, admin.id(), AssetRole.OWNER),
        admin);

    assertThatThrownBy(() -> lockService.setLocked(admin, library.getId(), false))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT diagnostics_locked FROM knowledge_libraries WHERE id = ?",
                Boolean.class,
                library.getId()))
        .isTrue();
  }

  /**
   * The same rule where the administrator does not have to create the grant row at all: a grant the
   * responsible body itself issued to the administration (here {@code VIEWER}) already exists, and
   * the administration raises it to {@code OWNER} in a single step. Only because {@code
   * AssetGrant#updateRole} carries the changer into {@code granted_by_user_id} does {@code
   * holdsIndependentOwnerRole} still see a self-procured {@code OWNER} - otherwise the row would
   * keep naming the original granter and the lock would open.
   */
  @Test
  void anAdministratorWhoRaisesAnExistingForeignGrantToOwnerStillCannotLiftAForeignLock() {
    KnowledgeLibrary library = persistLibraryOwnedBy(holderId);
    CurrentUser owner = CurrentUser.of(holderId, organizationId, SystemRole.USER, "Zustaendige");

    assetGrantService.upsertGrant(
        library.getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, admin.id(), AssetRole.VIEWER),
        owner);
    assetGrantService.upsertGrant(
        library.getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, admin.id(), AssetRole.OWNER),
        admin);

    assertThatThrownBy(() -> lockService.setLocked(admin, library.getId(), false))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT diagnostics_locked FROM knowledge_libraries WHERE id = ?",
                Boolean.class,
                library.getId()))
        .isTrue();
    assertThat(
            assetGrantRepository
                .findByLibraryIdAndSubjectTypeAndSubjectUserId(
                    library.getId(), PermissionSubjectType.USER, admin.id())
                .orElseThrow()
                .getGrantedByUserId())
        .isEqualTo(admin.id());
  }

  /**
   * The third shape of the same two-step path, and the one an unchanged-role check alone leaves
   * open: the administration holds a foreign {@code OWNER} grant that has long expired - {@code
   * holdsIndependentOwnerRole} discounts it - and merely extends its expiry at an unchanged role.
   * Only because {@code AssetGrant#updateRole} treats a revival as procuring the role does the row
   * stop naming the original conferrer, and the lock holds.
   */
  @Test
  void anAdministratorWhoRevivesAnExpiredForeignOwnerGrantStillCannotLiftAForeignLock() {
    KnowledgeLibrary library = persistLibraryOwnedBy(holderId);
    assetGrantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            organizationId,
            admin.id(),
            AssetRole.OWNER,
            Instant.now().minus(365, ChronoUnit.DAYS),
            holderId));

    assetGrantService.upsertGrant(
        library.getId(),
        new AssetGrantUpsert(
            PermissionSubjectType.USER,
            admin.id(),
            AssetRole.OWNER,
            Instant.now().plus(90, ChronoUnit.DAYS)),
        admin);

    assertThatThrownBy(() -> lockService.setLocked(admin, library.getId(), false))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT diagnostics_locked FROM knowledge_libraries WHERE id = ?",
                Boolean.class,
                library.getId()))
        .isTrue();
    assertThat(
            assetGrantRepository
                .findByLibraryIdAndSubjectTypeAndSubjectUserId(
                    library.getId(), PermissionSubjectType.USER, admin.id())
                .orElseThrow()
                .getGrantedByUserId())
        .isEqualTo(admin.id());
  }

  /** The counterpart: the named responsible body does lift its own lock. */
  @Test
  void theResponsibleOwnerLiftsTheLock() {
    KnowledgeLibrary library = persistLibraryOwnedBy(holderId);
    CurrentUser owner = CurrentUser.of(holderId, organizationId, SystemRole.USER, "Zustaendige");

    assertThat(lockService.setLocked(owner, library.getId(), false).isDiagnosticsLocked())
        .isFalse();
  }

  private KnowledgeLibrary persistLibraryOwnedBy(UUID ownerUserId) {
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                organizationId,
                "Personalvorgaenge " + UUID.randomUUID(),
                null,
                ownerUserId,
                LibraryVisibility.PRIVATE,
                false));
    assetGrantRepository.save(
        AssetGrant.forUser(
            library.getId(), organizationId, ownerUserId, AssetRole.OWNER, null, ownerUserId));
    return library;
  }

  private User persistUser(String subject) {
    User user = new User(subject + "-" + UUID.randomUUID(), "test-issuer", null, subject);
    user.setOrganizationId(organizationId);
    return userRepository.save(user);
  }
}
