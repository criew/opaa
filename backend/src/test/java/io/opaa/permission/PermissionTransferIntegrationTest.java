package io.opaa.permission;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AccessAsOfObjectType;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupSteward;
import io.opaa.group.GroupStewardRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.revision.AccessAsOfResult;
import io.opaa.revision.PointInTimeAccessService;
import io.opaa.space.Space;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceMembershipRepository;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The transfer of rights from one subject to another against the real, versioned schema (#1834,
 * ADR-0036 Entscheidung 10): the five effect kinds move in one operation, the history gets a clean
 * cut with one instant and one operation id, and the rules about who may transfer what hold.
 *
 * <p>Works in its own throwaway organizations, so no row of the shared fixture is touched - and
 * removes them, with the tables {@code OwnOrganizationFixtures} does not cover, afterwards.
 */
@OpaaIntegrationTest
class PermissionTransferIntegrationTest {

  @Autowired private PermissionTransferService transferService;
  @Autowired private PointInTimeAccessService pointInTimeAccess;
  @Autowired private PermissionHistoryService permissionHistoryService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private CapabilityGrantRepository capabilityGrantRepository;
  @Autowired private CapabilityService capabilityService;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceMembershipRepository spaceMembershipRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private LibraryAccessService libraryAccessService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationA;
  private UUID organizationB;
  private CurrentUser admin;

  @BeforeEach
  void createOrganizations() {
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Haus A")).getId();
    organizationB =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Haus B")).getId();
    admin = user(organizationA, SystemRole.SYSTEM_ADMIN);
  }

  /**
   * Ordered so that every child goes before its parent, and every row referencing a group before
   * the groups themselves: {@code knowledge_libraries.owner_group_id} and {@code
   * asset_grants.subject_group_id} are RESTRICT, so the libraries this class owns through a group
   * have to go here rather than in {@link OwnOrganizationFixtures}, which runs afterwards for the
   * rest (spaces, accounts, protocol, the organizations themselves).
   */
  @AfterEach
  void tearDown() {
    for (String table :
        List.of(
            "permission_transfer_objects",
            "permission_transfers",
            "capability_grant_history",
            "capability_grants",
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
          "DELETE FROM " + table + " WHERE organization_id IN (?, ?)",
          organizationA,
          organizationB);
    }
    ownOrganizationFixtures.removeOrganizations(organizationA, organizationB);
  }

  // -------------------------------------------------------------------------------------------
  // The whole operation
  // -------------------------------------------------------------------------------------------

  @Test
  void movesGrantsSpaceMembershipsCapabilitiesAndOwnershipInOneOperationAndLeavesTheSourceEmpty() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID granted = libraryOwnedBy(organizationA, admin.id());
    grantGroup(granted, source, AssetRole.VIEWER, null);
    UUID owned = groupOwnedLibrary(organizationA, source);
    Space space = space();
    spaceService.addMember(
        space.getId(), PermissionSubject.group(source, organizationA), SpaceRole.CURATOR, admin);
    capabilityService.grant(
        Capability.CREATE_INTERNAL_GROUP, CapabilitySubjectType.GROUP, source, admin);

    PermissionTransfer transfer = execute(order(source, target, everything()), admin);

    assertThat(transfer.counts().assetGrants()).isEqualTo(1);
    assertThat(transfer.counts().spaceMemberships()).isEqualTo(1);
    assertThat(transfer.counts().capabilities()).isEqualTo(1);
    assertThat(transfer.counts().ownedAssets()).isEqualTo(1);
    assertThat(grantRepository.existsBySubjectGroupId(source))
        .as("the source holds no grant any more - the reason behind the 409 of #1812 is gone")
        .isFalse();
    assertThat(capabilityGrantRepository.existsBySubjectGroupId(source)).isFalse();
    assertThat(spaceMembershipRepository.findSpaceMembershipsOfGroups(List.of(source))).isEmpty();
    assertThat(libraryRepository.existsByOwnerGroupId(source)).isFalse();
    assertThat(roleOfGroup(granted, target)).isEqualTo(AssetRole.VIEWER);
    assertThat(capabilityGrantRepository.existsBySubjectGroupId(target)).isTrue();
    assertThat(spaceMembershipRepository.findSpaceMembershipsOfGroups(List.of(target))).hasSize(1);
    assertThat(libraryRepository.findById(owned).orElseThrow().getOwnerId()).isEqualTo(target);
  }

  /**
   * Regression guard for the succession case #1819 builds on: a role at a library comes from grant
   * rows alone, so moving the owner column without the owner's grant leaves the successor with 403
   * on their own library and the departed owner with everything.
   */
  @Test
  void handsTheOwnersRoleOverWithTheOwnershipOfALibrary() {
    CurrentUser leaving = user(organizationA, SystemRole.USER);
    CurrentUser successor = user(organizationA, SystemRole.USER);
    UUID library = libraryCreatedBy(leaving);

    execute(
        new PermissionTransferOrder(
            PermissionSubjectType.USER,
            leaving.id(),
            PermissionSubjectType.USER,
            successor.id(),
            EnumSet.of(PermissionTransferScope.OWNERSHIP)),
        admin);

    assertThat(libraryRepository.findById(library).orElseThrow().getOwnerId())
        .isEqualTo(successor.id());
    assertThat(effectiveRoleOf(library, successor))
        .as("the successor must be able to act on the library they now own")
        .isEqualTo(AssetRole.OWNER);
    assertThat(effectiveRoleOf(library, leaving)).as("the departed owner keeps nothing").isNull();
  }

  /** The group-owned counterpart: ownership carries the group's MANAGER role with it. */
  @Test
  void handsTheManagerRoleOverWithTheOwnershipOfAGroupOwnedLibrary() {
    CurrentUser creator = user(organizationA, SystemRole.USER);
    UUID source = group(organizationA, "Referat 50", creator.id());
    UUID target = group(organizationA, "Referat 52");
    UUID library = groupLibraryCreatedBy(creator, source);

    execute(order(source, target, EnumSet.of(PermissionTransferScope.OWNERSHIP)), admin);

    assertThat(libraryRepository.findById(library).orElseThrow().getOwnerId()).isEqualTo(target);
    assertThat(roleOfGroup(library, target)).isEqualTo(AssetRole.MANAGER);
    assertThat(
            grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                KnowledgeLibrary.ASSET_TYPE, library, PermissionSubjectType.GROUP, source))
        .as("the source group holds nothing on the library any more")
        .isEmpty();
  }

  /**
   * The Stichtagsauskunft shows exactly one subject per object and day: the source's interval ends
   * at the same instant the target's begins, and both sides name the same operation.
   */
  @Test
  void cutsTheHistoryAtOneInstantUnderOneOperationId() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    AssetGrant grant = grantGroup(library, source, AssetRole.EDITOR, null);
    grantHistoryRepository.save(
        AssetGrantHistory.open(grant, AssetGrantHistoryCause.GRANTED, admin.id(), Instant.now()));

    PermissionTransfer transfer =
        execute(order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), admin);

    List<AssetGrantHistory> ofTransfer = intervalsOf(transfer);
    assertThat(ofTransfer).hasSize(2);
    assertThat(ofTransfer)
        .extracting(AssetGrantHistory::getCause)
        .containsExactlyInAnyOrder(
            AssetGrantHistoryCause.TRANSFERRED_OUT, AssetGrantHistoryCause.TRANSFERRED_IN);
    assertThat(ofTransfer)
        .extracting(AssetGrantHistory::getValidFrom)
        .containsOnly(transfer.getPerformedAt());
    assertThat(openIntervalOfGroup(library, source))
        .as("the source has no open interval left")
        .isNull();
    assertThat(
            grantHistoryRepository.findAll().stream()
                .filter(row -> source.equals(row.getSubjectGroupId()))
                .filter(row -> row.getCause() == AssetGrantHistoryCause.GRANTED)
                .map(AssetGrantHistory::getValidTo))
        .as("the source's state interval ends exactly where the target's begins")
        .containsOnly(transfer.getPerformedAt());
  }

  /**
   * The reading path of #1822 against the cut: the Stichtagsauskunft names the source's people up
   * to the transfer and the target's from it on - exactly one subject per instant - and the
   * zero-length {@code TRANSFERRED_OUT} marker appears as no access at all. It is an event, not a
   * state.
   */
  @Test
  void showsTheCutInTheStichtagsauskunftWithoutTheZeroLengthMarker() {
    CurrentUser auditor = user(organizationA, SystemRole.AUDITOR);
    CurrentUser leaving = user(organizationA, SystemRole.USER);
    CurrentUser arriving = user(organizationA, SystemRole.USER);
    UUID source = group(organizationA, "Referat 50", leaving.id());
    UUID target = group(organizationA, "Referat 52", arriving.id());
    UUID library = libraryOwnedBy(organizationA, admin.id());
    Instant windowStart = Instant.now().minusSeconds(3600);
    AssetGrant grant = grantGroup(library, source, AssetRole.VIEWER, null);
    grantHistoryRepository.save(
        AssetGrantHistory.open(grant, AssetGrantHistoryCause.GRANTED, admin.id(), windowStart));
    permissionHistoryService.recordMembershipAdded(
        source, organizationA, leaving.id(), GroupMembershipHistoryCause.ADDED, admin.id());
    permissionHistoryService.recordMembershipAdded(
        target, organizationA, arriving.id(), GroupMembershipHistoryCause.ADDED, admin.id());

    PermissionTransfer transfer =
        execute(order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), admin);

    AccessAsOfResult result =
        pointInTimeAccess.readersOf(
            organizationA,
            auditor.id(),
            "Beschwerde 4711",
            AccessAsOfObjectType.KNOWLEDGE_LIBRARY,
            library,
            windowStart.minusSeconds(60),
            Instant.now().plusSeconds(3600),
            0,
            50);

    assertThat(result.entries())
        .as("the marker of the source side is an event and never an access period")
        .noneMatch(entry -> entry.validTo() != null && entry.validTo().equals(entry.validFrom()));
    assertThat(result.entries())
        .filteredOn(entry -> leaving.id().equals(entry.userId()))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.basis()).isEqualTo(AccessBasis.GROUP_GRANT);
              assertThat(entry.validTo())
                  .as("the source's period ends exactly at the transfer")
                  .isEqualTo(transfer.getPerformedAt());
            });
    assertThat(result.entries())
        .filteredOn(entry -> arriving.id().equals(entry.userId()))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.validFrom())
                  .as("and the target's begins there")
                  .isEqualTo(transfer.getPerformedAt());
              assertThat(entry.validTo()).isNull();
            });
  }

  // -------------------------------------------------------------------------------------------
  // Where both sides meet on the same object
  // -------------------------------------------------------------------------------------------

  /** A transfer hands rights over; it never lowers what the target already held. */
  @Test
  void keepsTheStrongerRoleWhereBothSidesMeetOnTheSameObject() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    grantGroup(library, source, AssetRole.VIEWER, null);
    grantGroup(library, target, AssetRole.MANAGER, null);

    execute(order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), admin);

    assertThat(roleOfGroup(library, target)).isEqualTo(AssetRole.MANAGER);
    assertThat(grantRepository.existsBySubjectGroupId(source)).isFalse();
  }

  /**
   * The raising branch: the target already holds an <em>open</em> interval, which has to be closed
   * before the new one opens - the partial unique index allows at most one open interval per
   * subject and asset.
   */
  @Test
  void raisesTheTargetsRoleAndLeavesItWithExactlyOneOpenInterval() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    AssetGrant strong = grantGroup(library, source, AssetRole.MANAGER, null);
    AssetGrant weak = grantGroup(library, target, AssetRole.VIEWER, null);
    grantHistoryRepository.save(
        AssetGrantHistory.open(strong, AssetGrantHistoryCause.GRANTED, admin.id(), Instant.now()));
    grantHistoryRepository.save(
        AssetGrantHistory.open(weak, AssetGrantHistoryCause.GRANTED, admin.id(), Instant.now()));

    PermissionTransfer transfer =
        execute(order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), admin);

    assertThat(roleOfGroup(library, target)).isEqualTo(AssetRole.MANAGER);
    AssetGrantHistory open = openIntervalOfGroup(library, target);
    assertThat(open).isNotNull();
    assertThat(open.getCause()).isEqualTo(AssetGrantHistoryCause.TRANSFERRED_IN);
    assertThat(open.getValidFrom()).isEqualTo(transfer.getPerformedAt());
    assertThat(
            grantHistoryRepository.findAll().stream()
                .filter(row -> target.equals(row.getSubjectGroupId()))
                .filter(row -> row.getCause() == AssetGrantHistoryCause.GRANTED)
                .map(AssetGrantHistory::getValidTo))
        .as("the target's previous state interval ends where the new one begins")
        .containsOnly(transfer.getPerformedAt());
  }

  /** The same mechanic one axis further: a space the target is already a member of. */
  @Test
  void raisesTheTargetsSpaceRoleWhereBothGroupsAreMembers() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    Space space = space();
    spaceService.addMember(
        space.getId(), PermissionSubject.group(source, organizationA), SpaceRole.ADMIN, admin);
    spaceService.addMember(
        space.getId(), PermissionSubject.group(target, organizationA), SpaceRole.MEMBER, admin);

    execute(order(source, target, EnumSet.of(PermissionTransferScope.SPACE_MEMBERSHIPS)), admin);

    assertThat(spaceMembershipRepository.findSpaceMembershipsOfGroups(List.of(source))).isEmpty();
    assertThat(spaceMembershipRepository.findBySpaceId(space.getId()))
        .filteredOn(membership -> target.equals(membership.getGroupId()))
        .singleElement()
        .satisfies(membership -> assertThat(membership.getRole()).isEqualTo(SpaceRole.ADMIN));
  }

  // -------------------------------------------------------------------------------------------
  // Scope
  // -------------------------------------------------------------------------------------------

  /** "Umfang wählbar" is only a promise if what is left out actually stays where it was. */
  @Test
  void leavesEveryEffectOutsideTheChosenScopeWithTheSource() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    grantGroup(library, source, AssetRole.VIEWER, null);
    UUID owned = groupOwnedLibrary(organizationA, source);
    capabilityService.grant(
        Capability.CREATE_INTERNAL_GROUP, CapabilitySubjectType.GROUP, source, admin);

    PermissionTransfer transfer =
        execute(order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), admin);

    assertThat(transfer.counts().assetGrants()).isEqualTo(1);
    assertThat(transfer.counts().capabilities()).isZero();
    assertThat(transfer.counts().ownedAssets()).isZero();
    assertThat(capabilityGrantRepository.existsBySubjectGroupId(source))
        .as("a capability outside the scope stays with the source")
        .isTrue();
    assertThat(libraryRepository.findById(owned).orElseThrow().getOwnerId())
        .as("ownership outside the scope stays with the source")
        .isEqualTo(source);
  }

  /**
   * An expired grant confers nothing, so it is ended rather than handed on - and it is not counted,
   * or the preview would promise more reach than the operation delivers. Its row still has to go:
   * it blocks the RESTRICT key of a group deletion.
   */
  @Test
  void endsAnExpiredGrantOfTheSourceWithoutGrantingItAgain() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    grantGroup(library, source, AssetRole.VIEWER, Instant.now().minusSeconds(3600));

    PermissionTransferPreview preview =
        transferService.preview(
            order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), admin);
    PermissionTransfer transfer =
        transferService.transfer(
            order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)),
            true,
            preview.previewId(),
            admin);

    assertThat(preview.counts().assetGrants()).isZero();
    assertThat(transfer.counts().assetGrants()).isZero();
    assertThat(grantRepository.existsBySubjectGroupId(source)).isFalse();
    assertThat(
            grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                KnowledgeLibrary.ASSET_TYPE, library, PermissionSubjectType.GROUP, target))
        .as("a dead grant is not handed on")
        .isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // The note the objects carry afterwards
  // -------------------------------------------------------------------------------------------

  @Test
  void namesTheOperationAtEveryTouchedObjectAndTheSourceGroupWithIt() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID owned = groupOwnedLibrary(organizationA, source);

    PermissionTransfer transfer =
        execute(order(source, target, EnumSet.of(PermissionTransferScope.OWNERSHIP)), admin);

    PermissionTransferMark mark =
        transferService.markOf(KnowledgeLibrary.ASSET_TYPE, owned, admin).orElseThrow();
    assertThat(mark.transferId()).isEqualTo(transfer.getId());
    assertThat(mark.transferredAt()).isEqualTo(transfer.getPerformedAt());
    assertThat(mark.sourceLabel()).isEqualTo("Referat 50");
    assertThat(mark.sourceProtected()).isFalse();
  }

  /**
   * A hint repeated at many objects that a named colleague left the house is what this prevents.
   */
  @Test
  void namesNoPersonAtTheObjectWhenAPersonWasTheSource() {
    CurrentUser leaving = user(organizationA, SystemRole.USER);
    CurrentUser successor = user(organizationA, SystemRole.USER);
    UUID owned = libraryOwnedBy(organizationA, leaving.id());

    execute(
        new PermissionTransferOrder(
            PermissionSubjectType.USER,
            leaving.id(),
            PermissionSubjectType.USER,
            successor.id(),
            EnumSet.of(PermissionTransferScope.OWNERSHIP)),
        admin);

    PermissionTransferMark mark =
        transferService.markOf(KnowledgeLibrary.ASSET_TYPE, owned, admin).orElseThrow();
    assertThat(mark.sourceLabel()).isNull();
    assertThat(libraryRepository.findById(owned).orElseThrow().getOwnerId())
        .isEqualTo(successor.id());
  }

  /** A protected group appears in other people's lists by its protection, never by its name. */
  @Test
  void namesAProtectedSourceGroupToNobody() {
    UUID source = group(organizationA, "Personalrat");
    Group protectedGroup = groupRepository.findById(source).orElseThrow();
    protectedGroup.markProtected(true);
    groupRepository.save(protectedGroup);
    UUID target = group(organizationA, "Personalrat neu");
    UUID owned = groupOwnedLibrary(organizationA, source);

    execute(order(source, target, EnumSet.of(PermissionTransferScope.OWNERSHIP)), admin);

    PermissionTransferMark mark =
        transferService.markOf(KnowledgeLibrary.ASSET_TYPE, owned, admin).orElseThrow();
    assertThat(mark.sourceLabel()).isNull();
    assertThat(mark.sourceProtected()).isTrue();
  }

  /**
   * An internal group its stewards have not released is "not found" for an outsider - the note at
   * an object they may read must not be the one place that names it (ADR-0036, Entscheidung 9).
   */
  @Test
  void namesAnUnreleasedSourceGroupOnlyToPeopleWhoMaySeeIt() {
    UUID source = unreleasedGroup(organizationA, "Projektgruppe");
    UUID target = group(organizationA, "Referat 52");
    UUID owned = groupOwnedLibrary(organizationA, source);
    CurrentUser outsider = user(organizationA, SystemRole.USER);

    execute(order(source, target, EnumSet.of(PermissionTransferScope.OWNERSHIP)), admin);

    assertThat(
            transferService
                .markOf(KnowledgeLibrary.ASSET_TYPE, owned, outsider)
                .orElseThrow()
                .sourceLabel())
        .as("an ordinary reader of the library learns nothing about the group")
        .isNull();
    assertThat(
            transferService
                .markOf(KnowledgeLibrary.ASSET_TYPE, owned, admin)
                .orElseThrow()
                .sourceLabel())
        .isEqualTo("Projektgruppe");
  }

  // -------------------------------------------------------------------------------------------
  // Preview
  // -------------------------------------------------------------------------------------------

  @Test
  void countsWhatWouldMoveAndStandsInTheProtocolEvenWhenNobodyCarriesItOut() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    grantGroup(library, source, AssetRole.VIEWER, null);

    PermissionTransferPreview preview =
        transferService.preview(order(source, target, everything()), admin);

    assertThat(preview.previewId()).isNotNull();
    assertThat(preview.counts().assetGrants()).isEqualTo(1);
    assertThat(preview.grantedAssets()).isEqualTo(1);
    assertThat(preview.sourceLabel()).isEqualTo("Referat 50");
    assertThat(auditTypes(source))
        .containsExactly(AuditEventType.PERMISSION_TRANSFER_PREVIEWED.name());
    assertThat(grantRepository.existsBySubjectGroupId(source))
        .as("a preview moves nothing")
        .isTrue();
  }

  /** The execution runs against a preview or not at all - four ways to have none. */
  @Test
  void refusesAnExecutionWithoutAValidPreview() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID other = group(organizationA, "Referat 53");
    PermissionTransferOrder order = order(source, target, everything());

    assertThatThrownBy(() -> transferService.transfer(order, true, null, admin))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("keine gültige Vorschau");
    assertThatThrownBy(() -> transferService.transfer(order, true, UUID.randomUUID(), admin))
        .isInstanceOf(ConflictException.class);

    UUID previewOfAnotherOrder =
        transferService.preview(order(source, other, everything()), admin).previewId();
    assertThatThrownBy(() -> transferService.transfer(order, true, previewOfAnotherOrder, admin))
        .as("a preview is bound to the subjects and the scope it was taken for")
        .isInstanceOf(ConflictException.class);

    UUID foreignPreview = transferService.preview(order, admin).previewId();
    CurrentUser otherAdmin = user(organizationA, SystemRole.SYSTEM_ADMIN);
    assertThatThrownBy(() -> transferService.transfer(order, true, foreignPreview, otherAdmin))
        .as("a preview belongs to the caller it was shown to")
        .isInstanceOf(ConflictException.class);
  }

  /** What the preview showed must still stand - otherwise it is presented again, as a plan is. */
  @Test
  void refusesAnExecutionWhenTheStateChangedSinceThePreview() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    PermissionTransferOrder order =
        order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS));
    UUID previewId = transferService.preview(order, admin).previewId();

    grantGroup(library, source, AssetRole.VIEWER, null);

    assertThatThrownBy(() -> transferService.transfer(order, true, previewId, admin))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Stand hat sich");
    assertThat(grantRepository.existsBySubjectGroupId(source))
        .as("nothing was transferred")
        .isTrue();
  }

  /**
   * The preview of a person is not an "every effect of person X" query for the administration
   * (ADR-0036, Entscheidung 10; Personalrat Z4).
   */
  @Test
  void refusesToListOrMoveTheGrantsOfAPerson() {
    CurrentUser leaving = user(organizationA, SystemRole.USER);
    CurrentUser successor = user(organizationA, SystemRole.USER);

    assertThatThrownBy(
            () ->
                transferService.preview(
                    new PermissionTransferOrder(
                        PermissionSubjectType.USER,
                        leaving.id(),
                        PermissionSubjectType.USER,
                        successor.id(),
                        EnumSet.of(PermissionTransferScope.ASSET_GRANTS)),
                    admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur Eigentum und Verantwortung");
  }

  /** A group is responsible for nothing it could act on - the pairing has no meaning. */
  @Test
  void refusesAPersonAsSourceAndAGroupAsTarget() {
    CurrentUser person = user(organizationA, SystemRole.USER);
    UUID target = group(organizationA, "Referat 52");

    assertThatThrownBy(
            () ->
                transferService.preview(
                    new PermissionTransferOrder(
                        PermissionSubjectType.USER,
                        person.id(),
                        PermissionSubjectType.GROUP,
                        target,
                        EnumSet.of(PermissionTransferScope.OWNERSHIP)),
                    admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nicht an eine Gruppe");
  }

  // -------------------------------------------------------------------------------------------
  // Audit
  // -------------------------------------------------------------------------------------------

  @Test
  void recordsTheExecutionWithSourceTargetScopeAndTheNumberOfRows() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    grantGroup(library, source, AssetRole.VIEWER, null);

    execute(order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), admin);

    assertThat(auditTypes(source))
        .containsExactly(
            AuditEventType.PERMISSION_TRANSFER_PREVIEWED.name(),
            AuditEventType.PERMISSION_TRANSFER_EXECUTED.name());
    String payload =
        jdbcTemplate.queryForObject(
            "SELECT after FROM audit_log WHERE object_id = ? AND event_type = ?",
            String.class,
            source.toString(),
            AuditEventType.PERMISSION_TRANSFER_EXECUTED.name());
    assertThat(payload).contains(target.toString()).contains("ASSET_GRANTS").contains("\"rows\":1");
  }

  /** A person as the source is a subject, not just an object - the entry has to carry them so. */
  @Test
  void recordsAPersonAsSourceAsTheSubjectOfTheEntry() {
    CurrentUser leaving = user(organizationA, SystemRole.USER);
    CurrentUser successor = user(organizationA, SystemRole.USER);
    libraryOwnedBy(organizationA, leaving.id());

    execute(
        new PermissionTransferOrder(
            PermissionSubjectType.USER,
            leaving.id(),
            PermissionSubjectType.USER,
            successor.id(),
            EnumSet.of(PermissionTransferScope.OWNERSHIP)),
        admin);

    Integer withSubject =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = ? AND subject_ref IS NOT NULL"
                + " AND organization_id = ?",
            Integer.class,
            AuditEventType.PERMISSION_TRANSFER_EXECUTED.name(),
            organizationA);
    assertThat(withSubject).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Who may, and which targets are accepted
  // -------------------------------------------------------------------------------------------

  @Test
  void acceptsAnEffectiveButEmptyTargetGroupAndRefusesADissolvedOne() {
    UUID source = group(organizationA, "Referat 50");
    UUID empty = group(organizationA, "Referat 52");
    UUID dissolved = group(organizationA, "Referat 51");
    Group toDissolve = groupRepository.findById(dissolved).orElseThrow();
    toDissolve.dissolve(Instant.now());
    groupRepository.save(toDissolve);

    assertThat(transferService.preview(order(source, empty, everything()), admin)).isNotNull();
    assertThatThrownBy(() -> transferService.preview(order(source, dissolved, everything()), admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("aufgelöst");
  }

  @Test
  void neverCrossesTheOrganizationBoundary() {
    UUID source = group(organizationA, "Referat 50");
    UUID foreign = group(organizationB, "Referat 52");

    assertThatThrownBy(() -> transferService.preview(order(source, foreign, everything()), admin))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void demandsAnExplicitConfirmation() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    PermissionTransferOrder order = order(source, target, everything());
    UUID previewId = transferService.preview(order, admin).previewId();

    assertThatThrownBy(() -> transferService.transfer(order, false, previewId, admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bestätigt");
  }

  @Test
  void letsAPersonHandOverTheirOwnResponsibilityButNobodyElsesRights() {
    CurrentUser steward = user(organizationA, SystemRole.USER);
    CurrentUser successor = user(organizationA, SystemRole.USER);
    UUID group = group(organizationA, "Arbeitskreis");
    stewardRepository.save(new GroupSteward(group, steward.id(), organizationA, steward.id()));

    execute(
        new PermissionTransferOrder(
            PermissionSubjectType.USER,
            steward.id(),
            PermissionSubjectType.USER,
            successor.id(),
            EnumSet.of(PermissionTransferScope.STEWARDSHIP)),
        steward);

    assertThat(stewardRepository.existsByGroupIdAndUserId(group, successor.id())).isTrue();
    assertThat(stewardRepository.existsByGroupIdAndUserId(group, steward.id())).isFalse();
    assertThatThrownBy(
            () ->
                transferService.preview(
                    order(group, group(organizationA, "Referat 52"), everything()), steward))
        .isInstanceOf(AccessDeniedException.class);
  }

  /**
   * The one rule between an ordinary account and somebody else's holdings: a caller who is no
   * system administrator may name nobody but themselves as the source.
   */
  @Test
  void refusesAnOrdinaryAccountThatNamesAnotherPersonAsTheSource() {
    CurrentUser attacker = user(organizationA, SystemRole.USER);
    CurrentUser victim = user(organizationA, SystemRole.USER);
    libraryOwnedBy(organizationA, victim.id());

    assertThatThrownBy(
            () ->
                transferService.preview(
                    new PermissionTransferOrder(
                        PermissionSubjectType.USER,
                        victim.id(),
                        PermissionSubjectType.USER,
                        attacker.id(),
                        EnumSet.of(PermissionTransferScope.OWNERSHIP)),
                    attacker))
        .isInstanceOf(AccessDeniedException.class);
  }

  /**
   * The operation exists for a few hundred objects, not for a transaction without an end - and the
   * limit is decided by counting, before a single row is loaded, in the preview already.
   */
  @Test
  void refusesATransferAboveTheWorkLimitBeforeLoadingAnything() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    for (int i = 0; i <= PermissionTransferService.MAX_ROWS_PER_TRANSFER; i++) {
      // A grant names an existing asset (fk_asset_grants_asset_organization): the shell row alone
      // suffices, the transfer counts grants and never loads the library behind them.
      UUID assetId = UUID.randomUUID();
      jdbcTemplate.update(
          "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
              + " visibility) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Bibliothek', 'USER', ?,"
              + " 'PRIVATE')",
          assetId,
          organizationA,
          admin.id());
      grantRepository.save(
          AssetGrant.forGroup(
              KnowledgeLibrary.ASSET_TYPE,
              assetId,
              organizationA,
              source,
              AssetRole.VIEWER,
              null,
              admin.id(),
              null));
    }
    PermissionTransferOrder order =
        order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS));

    assertThatThrownBy(() -> transferService.preview(order, admin))
        .as("the figure is named before anybody confirms anything")
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("über der Grenze");
    assertThatThrownBy(() -> transferService.transfer(order, true, UUID.randomUUID(), admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("über der Grenze");
  }

  /**
   * The drift check is a print of the rows, not a count of them: a role changed between preview and
   * confirmation leaves the figures untouched and must still be caught.
   */
  @Test
  void refusesAnExecutionWhenARoleChangedSinceThePreviewWithoutChangingTheCounts() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    AssetGrant grant = grantGroup(library, source, AssetRole.VIEWER, null);
    PermissionTransferOrder order =
        order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS));
    PermissionTransferPreview preview = transferService.preview(order, admin);

    grant.updateRole(AssetRole.MANAGER, null, admin.id(), Instant.now());
    grantRepository.save(grant);

    assertThatThrownBy(() -> transferService.transfer(order, true, preview.previewId(), admin))
        .as("one row, one grant - and a different right than the one that was shown")
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Stand hat sich");
  }

  /**
   * One transfer can touch the target's grant twice - the grant part moves the source's role over,
   * the ownership part raises it to the role that goes with ownership. The second write must
   * correct the interval it just wrote, not close it: a state interval of zero length never held.
   */
  @Test
  void leavesNoZeroLengthStateIntervalWhenOneTransferRaisesTheSameGrantTwice() {
    CurrentUser creator = user(organizationA, SystemRole.USER);
    UUID source = group(organizationA, "Referat 50", creator.id());
    UUID target = group(organizationA, "Referat 52");
    UUID library = groupLibraryCreatedBy(creator, source);
    // Downgraded at its own library: the ownership part has to raise the target to MANAGER after
    // the grant part has already written VIEWER for it.
    AssetGrant ownerGrant =
        grantRepository
            .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                KnowledgeLibrary.ASSET_TYPE, library, PermissionSubjectType.GROUP, source)
            .orElseThrow();
    ownerGrant.updateRole(AssetRole.VIEWER, null, admin.id(), Instant.now());
    grantRepository.save(ownerGrant);

    PermissionTransfer transfer =
        execute(
            order(
                source,
                target,
                EnumSet.of(
                    PermissionTransferScope.ASSET_GRANTS, PermissionTransferScope.OWNERSHIP)),
            admin);

    assertThat(roleOfGroup(library, target)).isEqualTo(AssetRole.MANAGER);
    AssetGrantHistory open = openIntervalOfGroup(library, target);
    assertThat(open).isNotNull();
    assertThat(open.getRole()).isEqualTo(AssetRole.MANAGER);
    assertThat(open.getValidFrom()).isEqualTo(transfer.getPerformedAt());
    assertThat(
            grantHistoryRepository.findAll().stream()
                .filter(row -> target.equals(row.getSubjectGroupId()))
                .filter(row -> row.getValidTo() != null)
                .filter(row -> row.getValidTo().equals(row.getValidFrom()))
                .filter(row -> row.getCause() == AssetGrantHistoryCause.TRANSFERRED_IN))
        .as("a state interval that never held is not written")
        .isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /** Preview first, then execute - the one path production takes, and the only one that works. */
  private PermissionTransfer execute(PermissionTransferOrder order, CurrentUser caller) {
    UUID previewId = transferService.preview(order, caller).previewId();
    return transferService.transfer(order, true, previewId, caller);
  }

  private Set<PermissionTransferScope> everything() {
    return EnumSet.of(
        PermissionTransferScope.ASSET_GRANTS,
        PermissionTransferScope.SPACE_MEMBERSHIPS,
        PermissionTransferScope.CAPABILITIES,
        PermissionTransferScope.OWNERSHIP);
  }

  private PermissionTransferOrder order(
      UUID source, UUID target, Set<PermissionTransferScope> scope) {
    return new PermissionTransferOrder(
        PermissionSubjectType.GROUP, source, PermissionSubjectType.GROUP, target, scope);
  }

  private List<String> auditTypes(UUID objectId) {
    return jdbcTemplate.queryForList(
        "SELECT event_type FROM audit_log WHERE object_id = ? ORDER BY recorded_at",
        String.class,
        objectId.toString());
  }

  private AssetGrant grantGroup(UUID libraryId, UUID groupId, AssetRole role, Instant expiresAt) {
    return grantRepository.save(
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            libraryId,
            organizationA,
            groupId,
            role,
            expiresAt,
            admin.id(),
            null));
  }

  private AssetRole roleOfGroup(UUID libraryId, UUID groupId) {
    return grantRepository
        .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
            KnowledgeLibrary.ASSET_TYPE, libraryId, PermissionSubjectType.GROUP, groupId)
        .orElseThrow()
        .getRole();
  }

  private AssetGrantHistory openIntervalOfGroup(UUID libraryId, UUID groupId) {
    return grantHistoryRepository
        .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
            KnowledgeLibrary.ASSET_TYPE, libraryId, PermissionSubjectType.GROUP, groupId)
        .orElse(null);
  }

  private List<AssetGrantHistory> intervalsOf(PermissionTransfer transfer) {
    return grantHistoryRepository.findAll().stream()
        .filter(row -> transfer.getId().equals(row.getTransferId()))
        .toList();
  }

  private AssetRole effectiveRoleOf(UUID libraryId, CurrentUser who) {
    return libraryAccessService.effectiveRole(
        libraryRepository.findById(libraryId).orElseThrow(), who.id(), false);
  }

  private UUID group(UUID organizationId, String name, UUID... memberIds) {
    Group group = Group.internal(organizationId, name, null, null);
    group.release(true);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    UUID groupId = groupRepository.save(group).getId();
    membershipResolver.invalidateUsers(List.of(memberIds));
    return groupId;
  }

  /** The delivered state of a new internal group: nobody but its own people may name it. */
  private UUID unreleasedGroup(UUID organizationId, String name) {
    return groupRepository.save(Group.internal(organizationId, name, null, null)).getId();
  }

  private Space space() {
    return spaceService.createSpace(
        new SpaceCreation("Team", null, admin.id(), SpaceVisibility.PRIVATE, List.of(), null),
        admin);
  }

  private UUID libraryOwnedBy(UUID organizationId, UUID ownerUserId) {
    return libraryRepository
        .save(
            KnowledgeLibrary.ownedByUser(
                organizationId,
                "Bibliothek " + UUID.randomUUID(),
                null,
                ownerUserId,
                AssetVisibility.PRIVATE,
                false))
        .getId();
  }

  /**
   * Through the service, not the repository: only that path writes the owner's own {@code OWNER}
   * grant, and a fixture without it cannot show whether a transfer moves the role with the
   * ownership.
   */
  private UUID libraryCreatedBy(CurrentUser owner) {
    return libraryService
        .createLibrary(
            libraryCreation("Eigene Bibliothek " + UUID.randomUUID(), DocumentSourceType.UPLOAD)
                .ownerType(AssetOwnerType.USER)
                .ownerId(owner.id())
                .visibility(AssetVisibility.PRIVATE)
                .build(),
            owner)
        .library()
        .getId();
  }

  private UUID groupLibraryCreatedBy(CurrentUser creator, UUID ownerGroupId) {
    return libraryService
        .createLibrary(
            libraryCreation("Referatsbibliothek " + UUID.randomUUID(), DocumentSourceType.UPLOAD)
                .ownerType(AssetOwnerType.GROUP)
                .ownerId(ownerGroupId)
                .visibility(AssetVisibility.PRIVATE)
                .build(),
            creator)
        .library()
        .getId();
  }

  private UUID groupOwnedLibrary(UUID organizationId, UUID ownerGroupId) {
    return libraryRepository
        .save(
            KnowledgeLibrary.ownedByGroup(
                organizationId,
                "Referatsbibliothek " + UUID.randomUUID(),
                null,
                ownerGroupId,
                AssetVisibility.PRIVATE,
                false))
        .getId();
  }

  private CurrentUser user(UUID organizationId, SystemRole role) {
    User user =
        new User(
            UUID.randomUUID().toString(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.org",
            "Übertragung");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    User saved = userRepository.save(user);
    membershipResolver.invalidateUser(saved.getId());
    return CurrentUser.of(saved.getId(), organizationId, role, saved.getDisplayName());
  }
}
