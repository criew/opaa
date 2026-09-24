package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupMechanism;
import io.opaa.api.types.GroupOrigin;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetAccessDerivation;
import io.opaa.asset.AssetAccessDerivationService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceAccessDerivation;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.ProviderFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Herleitung "warum sehe ich das" (#1822, ADR-0036 Entscheidung 9): the own way named with the
 * group, its origin and its mechanism; the third-party answer reduced to the effective role where a
 * protected group is involved; and no answer at all for somebody the object does not reach.
 */
@OpaaIntegrationTest
class AccessDerivationIntegrationTest {

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private AssetAccessDerivationService derivationService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID providerId;
  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private final List<UUID> createdSpaceIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private CurrentUser member;
  private CurrentUser spaceAdmin;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Herleitung " + UUID.randomUUID()))
            .getId();
    providerId = ProviderFixtures.tokenProvider(providerRepository, "groups").getId();
    member = user("Frau Sommer", SystemRole.USER);
    spaceAdmin = user("Herr Winter", SystemRole.USER);
  }

  @AfterEach
  void tearDown() {
    createdSpaceIds.forEach(spaceRepository::deleteById);
    jdbcTemplate.update("DELETE FROM asset_grants WHERE organization_id = ?", organizationId);
    createdLibraryIds.forEach(libraryRepository::deleteById);
    jdbcTemplate.update("DELETE FROM group_memberships WHERE organization_id = ?", organizationId);
    createdGroupIds.forEach(groupRepository::deleteById);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    createdUserIds.forEach(id -> membershipResolver.invalidateUser(id));
    userRepository.deleteAllById(createdUserIds);
    providerRepository.deleteById(providerId);
    organizationRepository.deleteById(organizationId);
    createdSpaceIds.clear();
    createdLibraryIds.clear();
    createdGroupIds.clear();
    createdUserIds.clear();
  }

  @Test
  void aLibraryReachedThroughAGroupNamesTheGroupItsOriginAndItsMechanism() {
    UUID groupId = group("Referat 50", false);
    addMember(groupId, member.id());
    UUID libraryId = library(AssetVisibility.PRIVATE);
    grantToGroup(libraryId, groupId, AssetRole.EDITOR);

    AssetAccessDerivation derivation =
        derivationService.derive(KnowledgeLibrary.ASSET_TYPE, libraryId, member);

    assertThat(derivation.effectiveRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(derivation.paths()).hasSize(1);
    AccessPath path = derivation.paths().get(0);
    assertThat(path.basis()).isEqualTo(AccessBasis.GROUP_GRANT);
    assertThat(path.assetRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(path.since()).as("the moment of the grant is part of the answer").isNotNull();
    assertThat(path.group().name()).isEqualTo("Referat 50");
    assertThat(path.group().origin()).isEqualTo(GroupOrigin.PROVIDER);
    assertThat(path.group().mechanism()).isEqualTo(GroupMechanism.TOKEN);
    assertThat(path.group().providerName()).isNotBlank();
  }

  /** The organization-wide release is a way of its own, and it names nobody. */
  @Test
  void anOrganizationWideLibraryIsDerivedWithoutAnyGrant() {
    UUID libraryId = library(AssetVisibility.ORGANIZATION);

    AssetAccessDerivation derivation =
        derivationService.derive(KnowledgeLibrary.ASSET_TYPE, libraryId, member);

    assertThat(derivation.effectiveRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(derivation.paths())
        .singleElement()
        .satisfies(
            path -> {
              assertThat(path.basis()).isEqualTo(AccessBasis.ORGANIZATION_WIDE);
              assertThat(path.group()).isNull();
            });
  }

  /**
   * #1939: „Warum sehe ich diese Bibliothek?" steht im Reiter „Freigaben" für jede Rolle - ein
   * VIEWER ruft die Herleitung mit seiner eigenen Rolle ab, nicht mit einem 403.
   */
  @Test
  void aViewerCanRetrieveTheirOwnDerivationForAPrivateLibrary() {
    UUID libraryId = library(AssetVisibility.PRIVATE);
    grantToUser(libraryId, member.id(), AssetRole.VIEWER);

    AssetAccessDerivation derivation =
        derivationService.derive(KnowledgeLibrary.ASSET_TYPE, libraryId, member);

    assertThat(derivation.effectiveRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(derivation.paths())
        .singleElement()
        .satisfies(path -> assertThat(path.basis()).isEqualTo(AccessBasis.DIRECT_GRANT));
  }

  /** A library nothing reaches is "not found", not an empty derivation that confirms it exists. */
  @Test
  void aLibraryTheCallerDoesNotReachIsNotFound() {
    UUID libraryId = library(AssetVisibility.PRIVATE);

    assertThatThrownBy(
            () -> derivationService.derive(KnowledgeLibrary.ASSET_TYPE, libraryId, member))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aSpaceReachedThroughAGroupNamesTheGroupAndTheRoleItConfers() {
    UUID groupId = group("Projektgruppe Ost", false);
    addMember(groupId, member.id());
    UUID spaceId = space(spaceAdmin.id(), groupId, SpaceRole.CURATOR);

    SpaceAccessDerivation derivation = spaceService.accessDerivation(spaceId, null, member);

    assertThat(derivation.userId()).isEqualTo(member.id());
    assertThat(derivation.effectiveRole()).isEqualTo(SpaceRole.CURATOR);
    assertThat(derivation.pathsWithheld()).isFalse();
    assertThat(derivation.paths())
        .singleElement()
        .satisfies(
            path -> {
              assertThat(path.basis()).isEqualTo(AccessBasis.GROUP_MEMBERSHIP);
              assertThat(path.spaceRole()).isEqualTo(SpaceRole.CURATOR);
              assertThat(path.group().name()).isEqualTo("Projektgruppe Ost");
            });
  }

  /**
   * ADR-0036, Entscheidung 9: a protected group is not named to a third party - not even to the
   * space ADMIN who manages the membership - while the person's own derivation stays complete.
   */
  @Test
  void aProtectedGroupIsNamedToItsOwnMemberAndToNobodyElse() {
    UUID groupId = group("Personalrat", true);
    addMember(groupId, member.id());
    UUID spaceId = space(spaceAdmin.id(), groupId, SpaceRole.MEMBER);

    SpaceAccessDerivation thirdParty =
        spaceService.accessDerivation(spaceId, member.id(), spaceAdmin);
    assertThat(thirdParty.effectiveRole())
        .as("the effective role is still stated - only the way is withheld")
        .isEqualTo(SpaceRole.MEMBER);
    assertThat(thirdParty.paths()).isEmpty();
    assertThat(thirdParty.pathsWithheld()).isTrue();

    SpaceAccessDerivation own = spaceService.accessDerivation(spaceId, null, member);
    assertThat(own.pathsWithheld()).isFalse();
    assertThat(own.paths())
        .singleElement()
        .satisfies(path -> assertThat(path.group().name()).isEqualTo("Personalrat"));
  }

  /** Naming somebody else is reserved for those who manage the membership here. */
  @Test
  void anOrdinaryMemberCannotAskAboutSomebodyElse() {
    UUID groupId = group("Referat 51", false);
    addMember(groupId, member.id());
    UUID spaceId = space(spaceAdmin.id(), groupId, SpaceRole.MEMBER);

    assertThatThrownBy(() -> spaceService.accessDerivation(spaceId, spaceAdmin.id(), member))
        .isInstanceOf(AccessDeniedException.class);
  }

  private CurrentUser user(String displayName, SystemRole role) {
    User user =
        new User(
            "derivation-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return CurrentUser.of(id, organizationId, role, "Sachbearbeitung");
  }

  private UUID group(String name, boolean protectedGroup) {
    Group group =
        new Group(
            organizationId, GroupKind.IDENTITY_PROVIDER, name, null, providerId, name, null, null);
    group.markProtected(protectedGroup);
    UUID id = groupRepository.save(group).getId();
    createdGroupIds.add(id);
    return id;
  }

  private void addMember(UUID groupId, UUID userId) {
    jdbcTemplate.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, ?, now())",
        UUID.randomUUID(),
        userId,
        groupId,
        organizationId);
    membershipResolver.invalidateUser(userId);
  }

  private UUID library(AssetVisibility visibility) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId, "Bibliothek", null, spaceAdmin.id(), visibility, false);
    UUID id = libraryRepository.save(library).getId();
    createdLibraryIds.add(id);
    return id;
  }

  private void grantToUser(UUID libraryId, UUID userId, AssetRole role) {
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            libraryId,
            organizationId,
            userId,
            role,
            null,
            spaceAdmin.id()));
  }

  private void grantToGroup(UUID libraryId, UUID groupId, AssetRole role) {
    grantRepository.save(
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            libraryId,
            organizationId,
            groupId,
            role,
            null,
            spaceAdmin.id(),
            null));
  }

  private UUID space(UUID ownerId, UUID groupId, SpaceRole role) {
    Space space = new Space("Space", null, false, SpaceVisibility.PRIVATE, ownerId, organizationId);
    space.addMembership(SpaceMembership.ofUser(ownerId, SpaceRole.ADMIN, organizationId));
    space.addMembership(SpaceMembership.ofGroup(groupId, role, 1, organizationId));
    UUID id = spaceRepository.save(space).getId();
    createdSpaceIds.add(id);
    return id;
  }
}
