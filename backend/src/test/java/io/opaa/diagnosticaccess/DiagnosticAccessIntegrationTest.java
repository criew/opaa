package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.ProviderFixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

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
  @Autowired private DiagnosticContextRetentionService retentionService;
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
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private io.opaa.auth.oidc.OidcProviderRepository providerRepository;

  private UUID organizationId;
  private CurrentUser admin;
  private UUID holderId;
  private UUID scopeGroupId;
  private UUID scopeProviderId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Diagnostic Access Org"))
            .getId();
    holderId = persistUser("holder").getId();
    UUID adminId = persistUser("admin").getId();
    admin = CurrentUser.of(adminId, organizationId, SystemRole.SYSTEM_ADMIN, "Admin");
    // Every ORG_UNIT group carries its provider since #1816 (chk_groups_provider_kind).
    scopeProviderId = ProviderFixtures.tokenProvider(providerRepository).getId();
    scopeGroupId = providerGroup(GroupKind.ORG_UNIT, "Amt für Personal", 7).getId();
  }

  /**
   * A provider group with {@code activeMembers} usable accounts - a scope of the befugnis has to
   * carry at least the Mindestgruppengröße (#1879, ADR-0036 Entscheidung 3).
   */
  private Group providerGroup(GroupKind kind, String name, int activeMembers) {
    Group group = new Group(organizationId, kind, name, null, scopeProviderId, null, null, null);
    for (int index = 0; index < activeMembers; index++) {
      group.addMembership(
          new GroupMembership(persistUser("member-" + index).getId(), organizationId));
    }
    Group saved = groupRepository.save(group);
    membershipResolver.invalidateUsers(
        saved.getMemberships().stream().map(GroupMembership::getUserId).toList());
    return saved;
  }

  /**
   * Every row a test method writes belongs to the organization created above and is removed here,
   * in reference order - a class removes its own Bestand, whatever the schema's delete rules would
   * do with it. The organization itself stays, so that its protocol entries keep their referenced
   * object; no other class deletes organizations wholesale, so it is inconsequential. A Befugnis
   * written under a second organization escapes these deletes; {@code LeftoverRowGuard} reports it
   * for this class.
   */
  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM diagnostic_impersonation_grants WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM asset_grant_history WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM asset_grants WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM assets WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM group_memberships WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM group_membership_history WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM groups WHERE organization_id = ?", organizationId);
    // fk_groups_provider is RESTRICT, so the provider goes after its groups.
    providerRepository.deleteById(scopeProviderId);
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

  /**
   * The deletion path's revocation is only true if the deletion it belongs to commits, so {@code
   * Propagation.MANDATORY} refuses a call that would open a transaction of its own - what keeps a
   * later caller from revoking beside a deletion instead of inside it. The second half proves the
   * refusal is the propagation's and not some other failure of the same call.
   */
  @Test
  void theDeletionPathsRevocationRefusesToRunOutsideTheCallersTransaction() {
    UUID deletedAccountId = persistUser("issuer").getId();

    assertThatThrownBy(() -> grantService.revokeGrantsIssuedBy(admin, deletedAccountId))
        .isInstanceOf(IllegalTransactionStateException.class);
    List<DiagnosticImpersonationGrant> insideATransaction =
        transactionTemplate.execute(
            status -> grantService.revokeGrantsIssuedBy(admin, deletedAccountId));
    assertThat(insideATransaction).isEmpty();
  }

  /**
   * ADR-0036, Entscheidung 3: In a house that stays in token mode no {@code ORG_UNIT} group ever
   * comes into being - so a token group is a scope too, or the befugnis is never grantable there.
   */
  @Test
  void aTokenGroupOfAProviderIsAScope() {
    Group tokenGroup = providerGroup(GroupKind.IDENTITY_PROVIDER, "Meldewesen", 7);
    Instant from = Instant.now();

    DiagnosticImpersonationGrant granted =
        grantService.grant(
            admin,
            new DiagnosticImpersonationGrantCreation(
                holderId, tokenGroup.getId(), from, from.plus(30, ChronoUnit.DAYS)));

    assertThat(granted.getScopeGroupId()).isEqualTo(tokenGroup.getId());
  }

  /** An internal group stays out: it is nobody's Organisationseinheit, it is a house's own list. */
  @Test
  void anInternalGroupIsNoScope() {
    Group internal =
        groupRepository.save(Group.internal(organizationId, "Projektteam", null, null));
    Instant from = Instant.now();

    assertThatThrownBy(
            () ->
                grantService.grant(
                    admin,
                    new DiagnosticImpersonationGrantCreation(
                        holderId, internal.getId(), from, from.plus(30, ChronoUnit.DAYS))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Anbietergruppe");
  }

  /** A group whose provider is switched off reaches nobody, so it is no scope either. */
  @Test
  void aGroupOfASwitchedOffProviderIsNoScope() {
    Group scope = providerGroup(GroupKind.IDENTITY_PROVIDER, "Abgeschaltet", 7);
    providerRepository
        .findById(scopeProviderId)
        .ifPresent(
            provider -> {
              provider.disable();
              providerRepository.save(provider);
            });
    Instant from = Instant.now();

    assertThatThrownBy(
            () ->
                grantService.grant(
                    admin,
                    new DiagnosticImpersonationGrantCreation(
                        holderId, scope.getId(), from, from.plus(30, ChronoUnit.DAYS))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Identitätsanbieter");
  }

  /** Below the Mindestgruppengröße a group context discloses an individual - no scope at all. */
  @Test
  void aGroupBelowTheMinimumGroupSizeIsNoScope() {
    Group tooSmall = providerGroup(GroupKind.ORG_UNIT, "Kleine Einheit", 3);
    Instant from = Instant.now();

    assertThatThrownBy(
            () ->
                grantService.grant(
                    admin,
                    new DiagnosticImpersonationGrantCreation(
                        holderId, tooSmall.getId(), from, from.plus(30, ChronoUnit.DAYS))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Mindestgruppengröße");
  }

  /**
   * The acceptance criterion of #1879: seven active accounts at the time of granting, one at the
   * time of use. The size is therefore checked <b>on every use</b> - a grant that stays valid on
   * paper is no person context without the Schutzmechanik of one. The row itself is untouched: the
   * befugnis is not usable, not revoked, so neither the protocol nor the overview gets a break.
   */
  @Test
  void aBefugnisWhoseScopeShrankBelowTheMinimumIsNoLongerUsable() {
    Group scope = providerGroup(GroupKind.ORG_UNIT, "Referat 50", 7);
    UUID targetId = scope.getMemberships().iterator().next().getUserId();
    Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
    DiagnosticImpersonationGrant granted =
        grantService.grant(
            admin,
            new DiagnosticImpersonationGrantCreation(
                holderId, scope.getId(), from, from.plus(30, ChronoUnit.DAYS)));
    CurrentUser holder = CurrentUser.of(holderId, organizationId, SystemRole.USER, "Holder");
    assertThat(grantService.requireImpersonationPermission(holder, targetId).getId())
        .isEqualTo(granted.getId());

    // Six of the seven accounts are locked; the target person is the one that remains.
    scope.getMemberships().stream()
        .map(GroupMembership::getUserId)
        .filter(userId -> !userId.equals(targetId))
        .forEach(
            userId ->
                jdbcTemplate.update(
                    "UPDATE users SET directory_locked_at = now() WHERE id = ?", userId));
    membershipResolver.invalidateUsers(
        scope.getMemberships().stream().map(GroupMembership::getUserId).toList());

    assertThatThrownBy(() -> grantService.requireImpersonationPermission(holder, targetId))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("kleine Gruppe")
        .satisfies(
            refusal ->
                assertThat(refusal.getMessage())
                    .as("no figure below the Mindestgruppengröße leaves the house (ADR-0036/9)")
                    .doesNotContainPattern("\\d"));
    assertThat(grantService.holdsImpersonationPermission(holder))
        .as("an unusable befugnis is no selectable person context either")
        .isFalse();
    assertThat(grantRepository.findByIdAndOrganizationId(granted.getId(), organizationId))
        .get()
        .satisfies(
            stored -> {
              assertThat(stored.getRevokedAt())
                  .as("nothing was revoked - it is only unusable")
                  .isNull();
              assertThat(stored.isActiveAt(Instant.now())).isTrue();
            });
  }

  /**
   * A holder may legitimately hold several befugnisse, and the target person may be a member of two
   * scopes. The <b>usable</b> one decides - otherwise an unusable one hides it, and the interface's
   * own answer would disagree with the run.
   */
  @Test
  void aShrunkScopeDoesNotHideASecondUsableBefugnis() {
    Group shrinking = providerGroup(GroupKind.ORG_UNIT, "Referat 50", 7);
    Group intact = providerGroup(GroupKind.IDENTITY_PROVIDER, "Meldewesen", 7);
    UUID targetId = shrinking.getMemberships().iterator().next().getUserId();
    // The target person belongs to both scopes; the second one keeps its size.
    Group withTarget = groupRepository.findByIdWithMemberships(intact.getId()).orElseThrow();
    withTarget.addMembership(new GroupMembership(targetId, organizationId));
    groupRepository.save(withTarget);
    membershipResolver.invalidateUser(targetId);
    Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
    grantService.grant(
        admin,
        new DiagnosticImpersonationGrantCreation(
            holderId, shrinking.getId(), from, from.plus(30, ChronoUnit.DAYS)));
    DiagnosticImpersonationGrant usable =
        grantService.grant(
            admin,
            new DiagnosticImpersonationGrantCreation(
                holderId, intact.getId(), from, from.plus(30, ChronoUnit.DAYS)));
    CurrentUser holder = CurrentUser.of(holderId, organizationId, SystemRole.USER, "Holder");

    shrinking.getMemberships().stream()
        .map(GroupMembership::getUserId)
        .filter(userId -> !userId.equals(targetId))
        .forEach(
            userId ->
                jdbcTemplate.update(
                    "UPDATE users SET directory_locked_at = now() WHERE id = ?", userId));
    membershipResolver.invalidateUsers(
        shrinking.getMemberships().stream().map(GroupMembership::getUserId).toList());

    assertThat(grantService.requireImpersonationPermission(holder, targetId).getId())
        .as("the usable befugnis decides, whatever order the query returns")
        .isEqualTo(usable.getId());
    assertThat(grantService.holdsImpersonationPermission(holder)).isTrue();
  }

  /** The three states the diagnosis interface has to tell apart (#1879). */
  @Test
  void aHolderOfAnUnusableBefugnisIsNotAHolderOfNone() {
    Group scope = providerGroup(GroupKind.ORG_UNIT, "Referat 50", 7);
    CurrentUser holder = CurrentUser.of(holderId, organizationId, SystemRole.USER, "Holder");
    assertThat(grantService.impersonationAvailability(holder))
        .isEqualTo(DiagnosticImpersonationGrantService.ImpersonationAvailability.NONE);

    Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
    grantService.grant(
        admin,
        new DiagnosticImpersonationGrantCreation(
            holderId, scope.getId(), from, from.plus(30, ChronoUnit.DAYS)));
    assertThat(grantService.impersonationAvailability(holder))
        .isEqualTo(DiagnosticImpersonationGrantService.ImpersonationAvailability.USABLE);

    scope.getMemberships().stream()
        .map(GroupMembership::getUserId)
        .forEach(
            userId ->
                jdbcTemplate.update(
                    "UPDATE users SET directory_locked_at = now() WHERE id = ?", userId));
    membershipResolver.invalidateUsers(
        scope.getMemberships().stream().map(GroupMembership::getUserId).toList());

    assertThat(grantService.impersonationAvailability(holder))
        .isEqualTo(DiagnosticImpersonationGrantService.ImpersonationAvailability.SCOPE_TOO_SMALL);
  }

  /**
   * ADR-0036, Entscheidung 3: a token group its provider no longer maintains has a frozen
   * membership - no picture of the present, and therefore no scope. Same for a dissolved group,
   * which every other granting path refuses as well.
   */
  @Test
  void aFrozenOrDissolvedGroupIsNoScope() {
    Group frozen = providerGroup(GroupKind.IDENTITY_PROVIDER, "Eingefroren", 7);
    providerRepository
        .findById(scopeProviderId)
        .ifPresent(
            provider -> {
              provider.configureDirectorySync(true, 360);
              providerRepository.save(provider);
            });
    Instant from = Instant.now();

    assertThatThrownBy(
            () ->
                grantService.grant(
                    admin,
                    new DiagnosticImpersonationGrantCreation(
                        holderId, frozen.getId(), from, from.plus(30, ChronoUnit.DAYS))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("eingefroren");

    Group dissolved = providerGroup(GroupKind.ORG_UNIT, "Aufgelöst", 7);
    dissolved.dissolve(Instant.now());
    groupRepository.save(dissolved);

    assertThatThrownBy(
            () ->
                grantService.grant(
                    admin,
                    new DiagnosticImpersonationGrantCreation(
                        holderId, dissolved.getId(), from, from.plus(30, ChronoUnit.DAYS))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("aufgelöst");
  }

  @Test
  void aFreshlyCreatedLibraryIsDiagnosegesperrt() {
    KnowledgeLibrary saved =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                organizationId, "Personalvorgänge", null, holderId, false));

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
   * A change answers with what it wrote. The Vorher-Wert for the protocol entry is read into the
   * same persistence context the native update bypasses, so without clearing it the caller is
   * handed the value the change just replaced - while the row already carries the new one.
   */
  @Test
  void changingTheRetentionAnswersWithTheNewValueAndProtocolsBothValues() {
    int before = retentionRepository.findSingleton().orElseThrow().getRetentionMonths();
    int changed = before == 7 ? 9 : 7;

    DiagnosticContextRetentionSettings answered =
        retentionService.updateRetentionMonths(admin, changed);

    assertThat(answered.getRetentionMonths())
        .as("PUT must answer with the new value")
        .isEqualTo(changed);
    assertThat(retentionService.read(admin).getRetentionMonths())
        .as("a fresh read must see the new value")
        .isEqualTo(changed);
    Map<String, Object> protocolled =
        jdbcTemplate.queryForMap(
            "SELECT before, after FROM audit_log WHERE organization_id = ?"
                + " AND event_type = 'DIAGNOSTIC_CONTEXT_RETENTION_CHANGED'"
                + " ORDER BY recorded_at DESC LIMIT 1",
            organizationId);
    assertThat(retentionMonthsIn(protocolled.get("before"))).isEqualTo(before);
    assertThat(retentionMonthsIn(protocolled.get("after"))).isEqualTo(changed);
  }

  /**
   * The protocolled value as a number, read out of the entry's JSON rather than compared as raw
   * text: the invariant is the field, not the serializer's spacing or field order.
   */
  private int retentionMonthsIn(Object protocolledJson) {
    return JsonMapper.builder()
        .build()
        .readTree(String.valueOf(protocolledJson))
        .get("retentionMonths")
        .asInt();
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
        new Group(
            organizationId, GroupKind.AD_HOC, "Sachbearbeitung", null, null, null, null, null);
    // Released for use (#1814): an internal group its stewards have not released is no grant
    // subject for an owner who is neither member nor steward of it.
    profile.release(true);
    groupRepository.save(profile);
    assetGrantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        library.getId(),
        new AssetGrantUpsert(AssetGrantSubjectType.GROUP, profile.getId(), AssetRole.VIEWER),
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
        KnowledgeLibrary.ASSET_TYPE,
        library.getId(),
        new AssetGrantUpsert(AssetGrantSubjectType.USER, admin.id(), AssetRole.OWNER),
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
        KnowledgeLibrary.ASSET_TYPE,
        library.getId(),
        new AssetGrantUpsert(AssetGrantSubjectType.USER, admin.id(), AssetRole.VIEWER),
        owner);
    assetGrantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        library.getId(),
        new AssetGrantUpsert(AssetGrantSubjectType.USER, admin.id(), AssetRole.OWNER),
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
                .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserId(
                    KnowledgeLibrary.ASSET_TYPE,
                    library.getId(),
                    AssetGrantSubjectType.USER,
                    admin.id())
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
            KnowledgeLibrary.ASSET_TYPE,
            library.getId(),
            organizationId,
            admin.id(),
            AssetRole.OWNER,
            Instant.now().minus(365, ChronoUnit.DAYS),
            holderId));

    assetGrantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        library.getId(),
        new AssetGrantUpsert(
            AssetGrantSubjectType.USER,
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
                .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserId(
                    KnowledgeLibrary.ASSET_TYPE,
                    library.getId(),
                    AssetGrantSubjectType.USER,
                    admin.id())
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
                false));
    assetGrantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            library.getId(),
            organizationId,
            ownerUserId,
            AssetRole.OWNER,
            null,
            ownerUserId));
    return library;
  }

  private User persistUser(String subject) {
    User user = new User(subject + "-" + UUID.randomUUID(), "test-issuer", null, subject);
    user.setOrganizationId(organizationId);
    return userRepository.save(user);
  }
}
