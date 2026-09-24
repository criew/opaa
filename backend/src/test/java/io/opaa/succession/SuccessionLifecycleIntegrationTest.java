package io.opaa.succession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupSteward;
import io.opaa.group.GroupStewardRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryExternalAccessService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetType;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionSubject;
import io.opaa.space.Space;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceMemberView;
import io.opaa.space.SpaceOverview;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The lifecycle of ownership and responsibility against the real schema (#1819, ADR-0036
 * Entscheidung 6): the derived state, the detection run behind the age, the frozen reach and the
 * three tabs of the operational list.
 */
@OpaaIntegrationTest
class SuccessionLifecycleIntegrationTest {

  @Autowired private SuccessionService successionService;
  @Autowired private SuccessionDetectionService detectionService;
  @Autowired private SuccessionRetentionService retentionService;
  @Autowired private SuccessionCaseRepository cases;
  @Autowired private SuccessionReviewRepository reviews;
  @Autowired private LibraryExternalAccessService externalAccessService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraries;
  @Autowired private AssetGrantService grantService;
  @Autowired private SpaceService spaceService;
  @Autowired private GroupRepository groups;
  @Autowired private GroupStewardRepository stewards;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private CurrentUser admin;

  /**
   * The case rows found before the method: {@code runOnce} walks every organization of the shared
   * database, so a row it writes for a foreign one is this class's own and is cleaned up below.
   */
  private List<UUID> foreignCaseIds = List.of();

  /** Every organization this class created, the second one only for the run over all of them. */
  private final List<UUID> ownOrganizationIds = new ArrayList<>();

  @BeforeEach
  void createOrganization() {
    foreignCaseIds = jdbcTemplate.queryForList("SELECT id FROM succession_cases", UUID.class);
    organizationId =
        organizations
            .save(new Organization(UUID.randomUUID(), "Nachfolge " + UUID.randomUUID()))
            .getId();
    ownOrganizationIds.add(organizationId);
    admin = user(SystemRole.SYSTEM_ADMIN);
  }

  @AfterEach
  void tearDown() {
    // A pass over every organization writes rows for foreign ones too; they are this class's own.
    List<UUID> written =
        new ArrayList<>(jdbcTemplate.queryForList("SELECT id FROM succession_cases", UUID.class));
    written.removeAll(foreignCaseIds);
    for (UUID caseId : written) {
      jdbcTemplate.update("DELETE FROM succession_reviews WHERE case_id = ?", caseId);
      jdbcTemplate.update("DELETE FROM succession_cases WHERE id = ?", caseId);
    }
    for (UUID ownOrganizationId : ownOrganizationIds) {
      removeRowsOf(ownOrganizationId);
      ownOrganizationFixtures.removeOrganizations(ownOrganizationId);
    }
    ownOrganizationIds.clear();
  }

  private void removeRowsOf(UUID scopedOrganizationId) {
    for (String table :
        List.of(
            "succession_reviews",
            "succession_cases",
            "space_memberships",
            "space_membership_history",
            "asset_ownership_history",
            "asset_grants",
            "asset_grant_history",
            "asset_visibility_history",
            "knowledge_libraries",
            "assets",
            "group_membership_history",
            "group_memberships",
            "group_stewards",
            "groups")) {
      jdbcTemplate.update(
          "DELETE FROM " + table + " WHERE organization_id = ?", scopedOrganizationId);
    }
  }

  // -------------------------------------------------------------------------------------------
  // The derived state
  // -------------------------------------------------------------------------------------------

  /**
   * The acceptance criterion of the issue: the account is locked at once, its libraries go into the
   * state - and leave it by themselves when the account is usable again. No flag, no cleanup path.
   */
  @Test
  void aLibraryOfALockedAccountEntersAndLeavesTheStateByItself() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());

    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isFalse();

    lock(owner.id());
    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isTrue();

    unlock(owner.id());
    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library))
        .as("a derived state ends when its reason ends, without anybody clearing anything")
        .isFalse();
  }

  /** The group half: emptied of active accounts, then filled again. */
  @Test
  void aLibraryOfAGroupWithoutActiveMembersEntersAndLeavesTheState() {
    CurrentUser member = user(SystemRole.USER);
    UUID group = group("Referat 50", member.id());
    UUID library = libraryOwnedByGroup(group);

    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isFalse();

    lock(member.id());
    membershipResolver.invalidateUser(member.id());
    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isTrue();
    assertThat(
            successionService
                .findingFor(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)
                .orElseThrow()
                .addressee())
        .as("an internal group's library is the business of its stewards")
        .isEqualTo(SuccessionAddressee.GROUP_STEWARDS);

    unlock(member.id());
    membershipResolver.invalidateUser(member.id());
    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isFalse();
  }

  /** A dissolved group can act as little as an empty one - same state, same list. */
  @Test
  void aLibraryOfADissolvedGroupIsInTheState() {
    CurrentUser member = user(SystemRole.USER);
    UUID group = group("Referat 50", member.id());
    UUID library = libraryOwnedByGroup(group);
    Group dissolved = groups.findById(group).orElseThrow();
    dissolved.dissolve(Instant.now());
    groups.save(dissolved);

    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isTrue();
  }

  // -------------------------------------------------------------------------------------------
  // The frozen reach
  // -------------------------------------------------------------------------------------------

  /**
   * The object keeps working and keeps every right it has; only its reach is frozen - and the
   * refusal says who is responsible.
   */
  @Test
  void refusesToWidenTheReachOfAnObjectWhoseSuccessionIsOpenAndNamesTheAddressee() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    CurrentUser reader = user(SystemRole.USER);
    lock(owner.id());

    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    KnowledgeLibrary.ASSET_TYPE,
                    library,
                    new AssetGrantUpsert(PermissionSubjectType.USER, reader.id(), AssetRole.VIEWER),
                    admin))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Nachfolge offen")
        .hasMessageContaining("nichts wird")
        .hasMessageContaining("Systemverwaltung");
    assertThat(libraries.findById(library))
        .as("nothing is deleted and nothing is changed - the object stays as it is")
        .isPresent();
  }

  /** A space without a capable ADMIN takes no new members. */
  @Test
  void refusesANewSpaceMemberWhileTheSuccessionOfTheSpaceIsOpen() {
    CurrentUser owner = user(SystemRole.USER);
    Space space = space(owner);
    CurrentUser newcomer = user(SystemRole.USER);
    lock(owner.id());

    assertThatThrownBy(
            () ->
                spaceService.addMember(
                    space.getId(),
                    PermissionSubject.user(newcomer.id(), organizationId),
                    SpaceRole.MEMBER,
                    owner))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Nachfolge offen");
  }

  // -------------------------------------------------------------------------------------------
  // The detection run and the list
  // -------------------------------------------------------------------------------------------

  /** The run writes the first sighting and the end; the age of the list comes from it. */
  @Test
  void theRunRecordsTheFirstSightingAndTheEnd() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    lock(owner.id());

    detectionService.runFor(organizationId);
    SuccessionCase open =
        cases
            .findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
                SuccessionKind.OPEN_SUCCESSION, SuccessionObjectType.KNOWLEDGE_LIBRARY, library)
            .orElseThrow();
    assertThat(open.getFirstSeenAt()).isNotNull();
    assertThat(open.getClosedAt()).isNull();

    // A second pass keeps the first sighting - "Alter" must not restart at every look.
    detectionService.runFor(organizationId);
    SuccessionCase again =
        cases
            .findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
                SuccessionKind.OPEN_SUCCESSION, SuccessionObjectType.KNOWLEDGE_LIBRARY, library)
            .orElseThrow();
    assertThat(again.getId()).isEqualTo(open.getId());
    assertThat(again.getFirstSeenAt()).isEqualTo(open.getFirstSeenAt());

    unlock(owner.id());
    detectionService.runFor(organizationId);
    assertThat(cases.findById(open.getId()).orElseThrow().getClosedAt()).isNotNull();
  }

  /** The list holds every open succession from day one - before the run has ever looked. */
  @Test
  void theListIsCompleteBeforeTheRunHasSeenAnything() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    lock(owner.id());

    SuccessionPage page =
        successionService.list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50);

    assertThat(page.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.finding().objectId()).isEqualTo(library);
              assertThat(entry.caseId()).as("the record follows within the hour").isNull();
              assertThat(entry.firstSeenAt()).isNull();
              assertThat(entry.highlighted()).isFalse();
            });
  }

  /** "Freigaben ohne Empfänger": a group with grants and no active account left. */
  @Test
  void findsAGroupWithGrantsAndWithoutAnActiveMember() {
    CurrentUser member = user(SystemRole.USER);
    UUID group = group("Referat 50", member.id());
    UUID library = libraryOwnedByUser(admin.id());
    grantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        library,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group, AssetRole.VIEWER),
        admin);
    lock(member.id());
    membershipResolver.invalidateUser(member.id());

    SuccessionPage page =
        successionService.list(organizationId, SuccessionKind.GRANTS_WITHOUT_RECIPIENT, 0, 50);

    assertThat(page.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.finding().objectId()).isEqualTo(group);
              assertThat(entry.finding().affectedObjects())
                  .as("the tab names how many objects hang on the dead group")
                  .isEqualTo(1);
            });
  }

  /** "Gruppen ohne Wirkung": an internal group holding nothing and reaching nobody. */
  @Test
  void findsAnInternalGroupWithoutEffect() {
    UUID empty = group("Arbeitskreis ohne Wirkung");

    SuccessionPage page =
        successionService.list(organizationId, SuccessionKind.GROUP_WITHOUT_EFFECT, 0, 50);

    assertThat(page.entries()).extracting(entry -> entry.finding().objectId()).contains(empty);
  }

  /** An internal group without an active steward is an open succession, not a dead group. */
  @Test
  void findsAnInternalGroupWithoutAnActiveSteward() {
    CurrentUser steward = user(SystemRole.USER);
    UUID group = group("Arbeitskreis", steward.id());
    stewards.save(new GroupSteward(group, steward.id(), organizationId, steward.id()));
    lock(steward.id());
    membershipResolver.invalidateUser(steward.id());

    SuccessionPage page =
        successionService.list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50);

    assertThat(page.entries()).extracting(entry -> entry.finding().objectId()).contains(group);
  }

  /** The Sichtungsvermerk lifts the highlight and triggers nothing else. */
  @Test
  void aSichtungsvermerkIsRecordedAgainstTheCase() {
    CurrentUser owner = user(SystemRole.USER);
    libraryOwnedByUser(owner.id());
    lock(owner.id());
    detectionService.runFor(organizationId);
    UUID caseId =
        successionService
            .list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50)
            .entries()
            .get(0)
            .caseId();

    SuccessionReview review =
        successionService.review(caseId, "Nachfolge wird im Referat geklärt", admin);

    assertThat(review.getCaseId()).isEqualTo(caseId);
    assertThat(review.getReviewedByUserId()).isEqualTo(admin.id());
    assertThat(
            successionService
                .list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50)
                .entries()
                .get(0)
                .lastReviewReason())
        .isEqualTo("Nachfolge wird im Referat geklärt");
  }

  /**
   * The other half of the frozen reach, and the one a guard in the wrong place silently breaks:
   * everything that takes reach away stays possible - a downgrade, an expiry brought forward, a
   * release taken back, a member removed.
   */
  @Test
  void takingReachAwayStaysPossibleWhileTheSuccessionIsOpen() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    CurrentUser holder = user(SystemRole.USER);
    grantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        library,
        new AssetGrantUpsert(PermissionSubjectType.USER, holder.id(), AssetRole.MANAGER),
        admin);
    lock(owner.id());

    assertThat(
            grantService
                .upsertGrant(
                    KnowledgeLibrary.ASSET_TYPE,
                    library,
                    new AssetGrantUpsert(PermissionSubjectType.USER, holder.id(), AssetRole.VIEWER),
                    admin)
                .grant()
                .getRole())
        .as("a downgrade takes reach away and is never refused for an open succession")
        .isEqualTo(AssetRole.VIEWER);

    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    KnowledgeLibrary.ASSET_TYPE,
                    library,
                    new AssetGrantUpsert(
                        PermissionSubjectType.USER, holder.id(), AssetRole.MANAGER),
                    admin))
        .as("raising the same grant again is a widening and stays refused")
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Nachfolge offen");
  }

  /** The same distinction at the release across the Hausgrenze. */
  @Test
  void aRunningReleaseIsShortenedAndWithdrawnWhileTheSuccessionIsOpen() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    Instant late = Instant.now().plus(300, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(admin, library, true, late);
    lock(owner.id());

    assertThat(
            externalAccessService
                .setExternalAccess(admin, library, true, late.minus(200, ChronoUnit.DAYS))
                .expiresAt())
        .as("shortening a running release reaches nobody new")
        .isBefore(late);
    assertThatThrownBy(() -> externalAccessService.setExternalAccess(admin, library, true, late))
        .as("extending it again does")
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Nachfolge offen");

    externalAccessService.setExternalAccess(admin, library, false, null);
    assertThat(libraries.findById(library).orElseThrow().getExternalAccessState())
        .isEqualTo(ExternalAccessState.WITHDRAWN);
  }

  /** A space whose succession is open still loses members - only new ones are refused. */
  @Test
  void aSpaceMemberIsStillRemovedWhileTheSuccessionOfTheSpaceIsOpen() {
    CurrentUser owner = user(SystemRole.USER);
    Space space = space(owner);
    CurrentUser member = user(SystemRole.USER);
    SpaceMemberView added =
        spaceService.addMember(
            space.getId(),
            PermissionSubject.user(member.id(), organizationId),
            SpaceRole.MEMBER,
            owner);
    lock(owner.id());

    spaceService.removeMember(space.getId(), added.membership().getId(), owner);

    assertThat(spaceService.listMembers(space.getId(), admin))
        .extracting(view -> view.membership().getId())
        .doesNotContain(added.membership().getId());
  }

  /**
   * The personal space has no succession - and the boolean of the response says exactly what the
   * marking says, because both come from the one derivation (#1819 review finding 4).
   */
  @Test
  void thePersonalSpaceOfALockedAccountIsOpenInNeitherOfTheTwoFields() {
    CurrentUser owner = user(SystemRole.USER);
    spaceService.ensureDefaultSpace(owner.id(), organizationId);
    Space personal =
        spaceService.listSpaces(owner).stream()
            .map(SpaceOverview::space)
            .filter(Space::isDefault)
            .findFirst()
            .orElseThrow();
    lock(owner.id());

    assertThat(successionService.isOpen(SuccessionObjectType.SPACE, personal.getId())).isFalse();
    assertThat(spaceService.detailOf(personal, admin).successionOpen())
        .as("one derivation, one answer - the two fields of SpaceResponse cannot disagree")
        .isFalse();
    assertThat(spaceService.listSpaces(admin))
        .filteredOn(overview -> overview.space().getId().equals(personal.getId()))
        .allSatisfy(overview -> assertThat(overview.successionOpen()).isFalse());
  }

  /** The overview carries the same marking as the detail view (ADR-0036, Entscheidung 6). */
  @Test
  void theLibraryOverviewCarriesTheMarkingToo() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    grantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        library,
        new AssetGrantUpsert(PermissionSubjectType.USER, admin.id(), AssetRole.VIEWER),
        admin);
    lock(owner.id());

    assertThat(libraryService.listLibraries(admin))
        .filteredOn(summary -> summary.library().getId().equals(library))
        .singleElement()
        .satisfies(
            summary -> {
              assertThat(summary.succession()).isNotNull();
              assertThat(summary.succession().addressee())
                  .isEqualTo(SuccessionAddressee.SYSTEM_ADMINISTRATION);
              assertThat(summary.succession().membershipHints())
                  .as("the hints belong to the operational list, not to every reader's overview")
                  .isEmpty();
            });
  }

  /** "War Mitglied von …" is Bestandsinformation of the list, filled from the directory (E4). */
  @Test
  void theListNamesTheGroupsTheDepartedOwnerBelongedTo() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    group("Referat 50", owner.id());
    lock(owner.id());
    membershipResolver.invalidateUser(owner.id());

    assertThat(
            successionService.list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50).entries())
        .filteredOn(entry -> entry.finding().objectId().equals(library))
        .singleElement()
        .satisfies(entry -> assertThat(entry.finding().membershipHints()).contains("Referat 50"));
  }

  // -------------------------------------------------------------------------------------------
  // The age, the Sichtungsvermerk and the retention of the records
  // -------------------------------------------------------------------------------------------

  /** The only figure that makes this feature configurable - and what a Sichtungsvermerk does. */
  @Test
  void anAgedEntryIsHighlightedUntilASichtungsvermerkAndAgainAfterOnePeriod() {
    CurrentUser owner = user(SystemRole.USER);
    libraryOwnedByUser(owner.id());
    lock(owner.id());
    detectionService.runFor(organizationId);
    UUID caseId = firstEntry().caseId();
    backdateFirstSeen(caseId, 400);

    assertThat(firstEntry().highlighted())
        .as("older than the aging threshold of twelve months")
        .isTrue();

    successionService.review(caseId, "geprüft, Nachfolge in Vorbereitung", admin);
    assertThat(firstEntry().highlighted())
        .as("a Sichtungsvermerk lifts the highlight for one more period")
        .isFalse();

    jdbcTemplate.update(
        "UPDATE succession_reviews SET reviewed_at = now() - interval '400 days' WHERE case_id = ?",
        caseId);
    assertThat(firstEntry().highlighted())
        .as("and only for one - an aged Sichtungsvermerk highlights the entry again")
        .isTrue();
  }

  /**
   * ADR-0036, Entscheidung 8: the records are protocol and follow the protocol's window. A closed
   * record goes with its Sichtungsvermerke; an open one is never deleted, whatever its age.
   */
  @Test
  void theRetentionRunDeletesClosedRecordsWithTheirSichtungsvermerkeAndKeepsOpenOnes() {
    CurrentUser owner = user(SystemRole.USER);
    libraryOwnedByUser(owner.id());
    lock(owner.id());
    detectionService.runFor(organizationId);
    UUID closedId = firstEntry().caseId();
    successionService.review(closedId, "geprüft, weiterhin offen", admin);
    unlock(owner.id());
    detectionService.runFor(organizationId);
    jdbcTemplate.update(
        "UPDATE succession_cases SET closed_at = now() - interval '40 years' WHERE id = ?",
        closedId);
    CurrentUser stillGone = user(SystemRole.USER);
    libraryOwnedByUser(stillGone.id());
    lock(stillGone.id());
    detectionService.runFor(organizationId);
    UUID openId = firstEntry().caseId();
    jdbcTemplate.update(
        "UPDATE succession_cases SET first_seen_at = now() - interval '40 years' WHERE id = ?",
        openId);

    retentionService.runOnce();

    assertThat(cases.findById(closedId)).isEmpty();
    assertThat(reviews.findByCaseIdInOrderByReviewedAtDesc(List.of(closedId))).isEmpty();
    assertThat(cases.findById(openId))
        .as("an open record still describes something that holds")
        .isPresent();
  }

  /**
   * The production path of the run, which the scheduler takes: one transaction per organization,
   * and a failing organization neither takes the following ones with it nor leaves half its records
   * behind. Without the boundary the first record of the failing pass is committed by its own
   * repository transaction and survives - which is what this asserts against.
   */
  @Test
  void aFailingOrganizationNeitherStopsTheRunNorLeavesHalfItsRecordsBehind() {
    CurrentUser owner = user(SystemRole.USER);
    libraryOwnedByUser(owner.id());
    libraryOwnedByUser(owner.id());
    lock(owner.id());
    UUID otherOrganizationId = foreignOrganizationWithAnOpenSuccession();
    // What a transfer running beside the pass produces at uk_succession_cases_open - on the second
    // record of this organization, so the first one is already written when it hits.
    AtomicInteger savesInMyOrganization = new AtomicInteger();
    doThrow(new DataIntegrityViolationException("uk_succession_cases_open"))
        .when(cases)
        .save(
            argThat(
                candidate ->
                    candidate instanceof SuccessionCase written
                        && organizationId.equals(written.getOrganizationId())
                        && savesInMyOrganization.incrementAndGet() == 2));

    detectionService.runOnce();

    assertThat(cases.findByOrganizationIdAndClosedAtIsNull(organizationId))
        .as("one transaction per organization: the pass is rolled back whole, not half")
        .isEmpty();
    assertThat(cases.findByOrganizationIdAndClosedAtIsNull(otherOrganizationId))
        .as("and the organizations behind the failing one still get their pass")
        .hasSize(1);
  }

  // -------------------------------------------------------------------------------------------
  // What an operation may close
  // -------------------------------------------------------------------------------------------

  /**
   * A transfer that did not end the state must leave the record alone: closing and reopening would
   * restart the age the list shows.
   */
  @Test
  void anOperationClosesOnlyWhatItReallyEndedAndOnlyItsOwnTab() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    lock(owner.id());
    detectionService.runFor(organizationId);
    UUID caseId = firstEntry().caseId();

    successionService.closeForAsset(KnowledgeLibrary.ASSET_TYPE, library, admin.id());
    assertThat(cases.findById(caseId).orElseThrow().getClosedAt())
        .as("the state still holds - the record keeps its age")
        .isNull();

    unlock(owner.id());
    successionService.closeForAsset(KnowledgeLibrary.ASSET_TYPE, library, admin.id());
    SuccessionCase closed = cases.findById(caseId).orElseThrow();
    assertThat(closed.getClosedAt()).isNotNull();
    assertThat(closed.getClosedByUserId()).isEqualTo(admin.id());
  }

  /** An asset type no source answers for has no record to close (#1726) - and no exception. */
  @Test
  void anUnknownAssetTypeClosesNothingAndThrowsNothing() {
    successionService.closeForAsset(new AssetType("PROMPT_LIBRARY"), UUID.randomUUID(), admin.id());
  }

  /** The other tabs answer another question and are not touched by a transfer of ownership. */
  @Test
  void aTransferDoesNotCloseTheRecordOfAnotherTab() {
    UUID group = group("Arbeitskreis ohne Wirkung");
    detectionService.runFor(organizationId);
    SuccessionCase withoutEffect =
        cases
            .findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
                SuccessionKind.GROUP_WITHOUT_EFFECT, SuccessionObjectType.GROUP, group)
            .orElseThrow();

    successionService.closeForGroup(group, admin.id());

    assertThat(cases.findById(withoutEffect.getId()).orElseThrow().getClosedAt())
        .as("the group is still without effect - this record is about another question")
        .isNull();
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private SuccessionEntry firstEntry() {
    return successionService
        .list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50)
        .entries()
        .get(0);
  }

  private void backdateFirstSeen(UUID caseId, int days) {
    jdbcTemplate.update(
        "UPDATE succession_cases SET first_seen_at = now() - make_interval(days => ?) WHERE id = ?",
        days,
        caseId);
  }

  /** A second organization with one open succession, so a pass over all of them has two. */
  private UUID foreignOrganizationWithAnOpenSuccession() {
    UUID foreignId =
        organizations
            .save(new Organization(UUID.randomUUID(), "Nachbarhaus " + UUID.randomUUID()))
            .getId();
    ownOrganizationIds.add(foreignId);
    User owner =
        new User(
            UUID.randomUUID().toString(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.org",
            "Nachbarhaus");
    owner.setOrganizationId(foreignId);
    owner.setSystemRole(SystemRole.USER);
    UUID ownerId = users.save(owner).getId();
    libraries.save(
        KnowledgeLibrary.ownedByUser(
            foreignId,
            "Bibliothek " + UUID.randomUUID(),
            null,
            ownerId,
            AssetVisibility.PRIVATE,
            false));
    lock(ownerId);
    return foreignId;
  }

  private void lock(UUID userId) {
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", userId);
  }

  private void unlock(UUID userId) {
    jdbcTemplate.update("UPDATE users SET directory_locked_at = NULL WHERE id = ?", userId);
  }

  private UUID libraryOwnedByUser(UUID ownerId) {
    return libraries
        .save(
            KnowledgeLibrary.ownedByUser(
                organizationId,
                "Bibliothek " + UUID.randomUUID(),
                null,
                ownerId,
                AssetVisibility.PRIVATE,
                false))
        .getId();
  }

  private UUID libraryOwnedByGroup(UUID groupId) {
    return libraries
        .save(
            KnowledgeLibrary.ownedByGroup(
                organizationId,
                "Referatsbibliothek " + UUID.randomUUID(),
                null,
                groupId,
                AssetVisibility.PRIVATE,
                false))
        .getId();
  }

  private Space space(CurrentUser owner) {
    return spaceService.createSpace(
        new SpaceCreation("Team", null, owner.id(), SpaceVisibility.PRIVATE, List.of(), null),
        owner);
  }

  private UUID group(String name, UUID... memberIds) {
    Group group = Group.internal(organizationId, name, null, null);
    group.release(true);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    UUID groupId = groups.save(group).getId();
    membershipResolver.invalidateUsers(List.of(memberIds));
    return groupId;
  }

  private CurrentUser user(SystemRole role) {
    User user =
        new User(
            UUID.randomUUID().toString(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.org",
            "Nachfolge");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    User saved = users.save(user);
    membershipResolver.invalidateUser(saved.getId());
    return CurrentUser.of(saved.getId(), organizationId, role, saved.getDisplayName());
  }
}
