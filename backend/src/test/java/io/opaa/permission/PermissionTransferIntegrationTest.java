package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupSteward;
import io.opaa.group.GroupStewardRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
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
 * ADR-0036 Entscheidung 10): the four effect kinds move in one operation, the history gets a clean
 * cut with one instant and one operation id, and the rules about who may transfer what hold.
 *
 * <p>Works in its own throwaway organizations, so no row of the shared fixture is touched - and
 * removes them, with the tables {@code OwnOrganizationFixtures} does not cover, afterwards.
 */
@OpaaIntegrationTest
class PermissionTransferIntegrationTest {

  @Autowired private PermissionTransferService transferService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private CapabilityGrantRepository capabilityGrantRepository;
  @Autowired private CapabilityService capabilityService;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceMembershipRepository spaceMembershipRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
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
    grantRepository.save(
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            granted,
            organizationA,
            source,
            AssetRole.VIEWER,
            null,
            admin.id()));
    UUID owned = groupOwnedLibrary(organizationA, source);
    Space space = space();
    spaceService.addMember(
        space.getId(), PermissionSubject.group(source, organizationA), SpaceRole.CURATOR, admin);
    capabilityService.grant(
        Capability.CREATE_INTERNAL_GROUP, CapabilitySubjectType.GROUP, source, admin);

    PermissionTransfer transfer =
        transferService.transfer(order(source, target, everything()), true, admin);

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
    assertThat(
            grantRepository
                .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                    KnowledgeLibrary.ASSET_TYPE, granted, PermissionSubjectType.GROUP, target)
                .orElseThrow()
                .getRole())
        .isEqualTo(AssetRole.VIEWER);
    assertThat(capabilityGrantRepository.existsBySubjectGroupId(target)).isTrue();
    assertThat(spaceMembershipRepository.findSpaceMembershipsOfGroups(List.of(target))).hasSize(1);
    assertThat(libraryRepository.findById(owned).orElseThrow().getOwnerId()).isEqualTo(target);
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
    AssetGrant grant =
        grantRepository.save(
            AssetGrant.forGroup(
                KnowledgeLibrary.ASSET_TYPE,
                library,
                organizationA,
                source,
                AssetRole.EDITOR,
                null,
                admin.id()));
    grantHistoryRepository.save(
        AssetGrantHistory.open(grant, AssetGrantHistoryCause.GRANTED, admin.id(), Instant.now()));

    PermissionTransfer transfer =
        transferService.transfer(
            order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), true, admin);

    List<AssetGrantHistory> ofTransfer =
        grantHistoryRepository.findAll().stream()
            .filter(row -> transfer.getId().equals(row.getTransferId()))
            .toList();
    assertThat(ofTransfer).hasSize(2);
    assertThat(ofTransfer)
        .extracting(AssetGrantHistory::getCause)
        .containsExactlyInAnyOrder(
            AssetGrantHistoryCause.TRANSFERRED_OUT, AssetGrantHistoryCause.TRANSFERRED_IN);
    assertThat(ofTransfer)
        .extracting(AssetGrantHistory::getValidFrom)
        .containsOnly(transfer.getPerformedAt());
    AssetGrantHistory closedSourceInterval =
        grantHistoryRepository
            .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
                KnowledgeLibrary.ASSET_TYPE, library, PermissionSubjectType.GROUP, source)
            .orElse(null);
    assertThat(closedSourceInterval).as("the source has no open interval left").isNull();
    assertThat(
            grantHistoryRepository.findAll().stream()
                .filter(row -> source.equals(row.getSubjectGroupId()))
                .filter(row -> row.getCause() == AssetGrantHistoryCause.GRANTED)
                .map(AssetGrantHistory::getValidTo))
        .as("the source's state interval ends exactly where the target's begins")
        .containsOnly(transfer.getPerformedAt());
  }

  /** A transfer hands rights over; it never lowers what the target already held. */
  @Test
  void keepsTheStrongerRoleWhereBothSidesMeetOnTheSameObject() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    grantRepository.save(
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            library,
            organizationA,
            source,
            AssetRole.VIEWER,
            null,
            admin.id()));
    grantRepository.save(
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            library,
            organizationA,
            target,
            AssetRole.MANAGER,
            null,
            admin.id()));

    transferService.transfer(
        order(source, target, EnumSet.of(PermissionTransferScope.ASSET_GRANTS)), true, admin);

    assertThat(
            grantRepository
                .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                    KnowledgeLibrary.ASSET_TYPE, library, PermissionSubjectType.GROUP, target)
                .orElseThrow()
                .getRole())
        .isEqualTo(AssetRole.MANAGER);
    assertThat(grantRepository.existsBySubjectGroupId(source)).isFalse();
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
        transferService.transfer(
            order(source, target, EnumSet.of(PermissionTransferScope.OWNERSHIP)), true, admin);

    PermissionTransferMark mark =
        transferService.markOf(KnowledgeLibrary.ASSET_TYPE, owned).orElseThrow();
    assertThat(mark.transferId()).isEqualTo(transfer.getId());
    assertThat(mark.transferredAt()).isEqualTo(transfer.getPerformedAt());
    assertThat(mark.sourceLabel()).isEqualTo("Referat 50");
  }

  /**
   * A hint repeated at many objects that a named colleague left the house is what this prevents.
   */
  @Test
  void namesNoPersonAtTheObjectWhenAPersonWasTheSource() {
    CurrentUser leaving = user(organizationA, SystemRole.USER);
    CurrentUser successor = user(organizationA, SystemRole.USER);
    UUID owned = libraryOwnedBy(organizationA, leaving.id());

    transferService.transfer(
        new PermissionTransferOrder(
            PermissionSubjectType.USER,
            leaving.id(),
            PermissionSubjectType.USER,
            successor.id(),
            EnumSet.of(PermissionTransferScope.OWNERSHIP)),
        true,
        admin);

    PermissionTransferMark mark =
        transferService.markOf(KnowledgeLibrary.ASSET_TYPE, owned).orElseThrow();
    assertThat(mark.sourceLabel()).isNull();
    assertThat(libraryRepository.findById(owned).orElseThrow().getOwnerId())
        .isEqualTo(successor.id());
  }

  // -------------------------------------------------------------------------------------------
  // Preview
  // -------------------------------------------------------------------------------------------

  @Test
  void countsWhatWouldMoveAndStandsInTheProtocolEvenWhenNobodyCarriesItOut() {
    UUID source = group(organizationA, "Referat 50");
    UUID target = group(organizationA, "Referat 52");
    UUID library = libraryOwnedBy(organizationA, admin.id());
    grantRepository.save(
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            library,
            organizationA,
            source,
            AssetRole.VIEWER,
            null,
            admin.id()));

    PermissionTransferPreview preview =
        transferService.preview(order(source, target, everything()), admin);

    assertThat(preview.counts().assetGrants()).isEqualTo(1);
    assertThat(preview.grantedAssets()).isEqualTo(1);
    assertThat(preview.sourceLabel()).isEqualTo("Referat 50");
    assertThat(auditTypes(source))
        .containsExactly(AuditEventType.PERMISSION_TRANSFER_PREVIEWED.name());
    assertThat(grantRepository.existsBySubjectGroupId(source))
        .as("a preview moves nothing")
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

    assertThatThrownBy(
            () -> transferService.transfer(order(source, target, everything()), false, admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bestätigt");
  }

  @Test
  void letsAPersonHandOverTheirOwnResponsibilityButNobodyElsesRights() {
    CurrentUser steward = user(organizationA, SystemRole.USER);
    CurrentUser successor = user(organizationA, SystemRole.USER);
    UUID group = group(organizationA, "Arbeitskreis");
    stewardRepository.save(new GroupSteward(group, steward.id(), organizationA, steward.id()));

    transferService.transfer(
        new PermissionTransferOrder(
            PermissionSubjectType.USER,
            steward.id(),
            PermissionSubjectType.USER,
            successor.id(),
            EnumSet.of(PermissionTransferScope.STEWARDSHIP)),
        true,
        steward);

    assertThat(stewardRepository.existsByGroupIdAndUserId(group, successor.id())).isTrue();
    assertThat(stewardRepository.existsByGroupIdAndUserId(group, steward.id())).isFalse();
    assertThatThrownBy(
            () ->
                transferService.preview(
                    order(group, group(organizationA, "Referat 52"), everything()), steward))
        .isInstanceOf(AccessDeniedException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

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
        "SELECT event_type FROM audit_log WHERE object_id = ?", String.class, objectId.toString());
  }

  private UUID group(UUID organizationId, String name) {
    Group group = Group.internal(organizationId, name, null, null);
    group.release(true);
    return groupRepository.save(group).getId();
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
                LibraryVisibility.PRIVATE,
                false))
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
                LibraryVisibility.PRIVATE,
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
