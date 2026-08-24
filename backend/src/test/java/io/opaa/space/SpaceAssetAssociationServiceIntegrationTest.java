package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.CurrentUser;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.ChatRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryVisibility;
import io.opaa.notification.Notification;
import io.opaa.notification.NotificationRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied - same
 * pattern as {@link SpaceServiceIntegrationTest} (#288).
 */
@OpaaIntegrationTest
class SpaceAssetAssociationServiceIntegrationTest {

  @Autowired private SpaceAssetAssociationService associationService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository membershipRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService libraryAccessService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private SpaceAssetAssociationRepository associationRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationA;

  @BeforeEach
  void setUp() {
    chatRepository.deleteAll();
    associationRepository.deleteAll();
    notificationRepository.deleteAll();
    membershipRepository.deleteAll();
    spaceRepository.deleteAll();
    grantRepository.deleteAll();
    libraryRepository.deleteAll();
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
  }

  @AfterEach
  void tearDown() {
    chatRepository.deleteAll();
    associationRepository.deleteAll();
    notificationRepository.deleteAll();
    membershipRepository.deleteAll();
    spaceRepository.deleteAll();
    grantRepository.deleteAll();
    libraryRepository.deleteAll();
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationA);
    organizationRepository.deleteById(organizationA);
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationA);
    return userRepository.save(user).getId();
  }

  private UUID createSpace(UUID ownerId, SpaceRole ownerRole) {
    Space space =
        new Space("Fachbereich", null, false, SpaceVisibility.PRIVATE, ownerId, organizationA);
    space.addMembership(new SpaceMembership(ownerId, ownerRole, organizationA));
    return spaceRepository.save(space).getId();
  }

  private void addMember(UUID spaceId, UUID userId, SpaceRole role) {
    Space space = spaceRepository.findByIdWithMemberships(spaceId).orElseThrow();
    space.addMembership(new SpaceMembership(userId, role, organizationA));
    spaceRepository.save(space);
  }

  private UUID createLibrary(UUID ownerId) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationA, "Bibliothek", null, ownerId, LibraryVisibility.PRIVATE, false);
    return libraryRepository.save(library).getId();
  }

  private void grant(UUID libraryId, UUID userId, AssetRole role) {
    grantRepository.save(AssetGrant.forUser(libraryId, organizationA, userId, role, null, userId));
  }

  private CurrentUser currentUserOf(UUID userId) {
    return currentUserOf(userId, false);
  }

  private CurrentUser currentUserOf(UUID userId, boolean systemAdmin) {
    return new CurrentUser(
        userId, organizationA, systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER, "Caller");
  }

  @Test
  void associatingALibraryChangesNoOnesEffectivePermissions() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID member = createUser();
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    addMember(space, member, SpaceRole.CURATOR);

    Set<UUID> readableBefore = libraryAccessService.readableLibraryIds(member, organizationA);
    associationService.associate(space, library, currentUserOf(owner));
    Set<UUID> readableAfter = libraryAccessService.readableLibraryIds(member, organizationA);

    assertThat(readableBefore).doesNotContain(library);
    assertThat(readableAfter).doesNotContain(library);
  }

  @Test
  void curatorCanAssociateALibraryTheyThemselvesCanRead() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);

    SpaceLibraryLink response = associationService.associate(space, library, currentUserOf(owner));

    assertThat(response.association().getLibraryId()).isEqualTo(library);
  }

  // #706 review, finding 6: 404, not 403 - a plain 403 here would let a caller distinguish "this
  // library exists in my organization but I lack access" from "no such library" for any guessed
  // id, the exact existence-oracle gap #436 already closed for every other library-scoped
  // endpoint (LibraryAccessService#requireRole).
  @Test
  void curatorCannotAssociateALibraryTheyThemselvesCannotRead() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    UUID space = createSpace(curator, SpaceRole.ADMIN);

    assertThatThrownBy(() -> associationService.associate(space, library, currentUserOf(curator)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void plainMemberCannotAssociateALibrary() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID member = createUser();
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    addMember(space, member, SpaceRole.MEMBER);
    grant(library, member, AssetRole.VIEWER);

    assertThatThrownBy(() -> associationService.associate(space, library, currentUserOf(member)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void twoMembersWithDifferentGrantsSeeDifferentAssociatedLibraryLists() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    associationService.associate(space, library, currentUserOf(owner));

    UUID memberWithAccess = createUser();
    addMember(space, memberWithAccess, SpaceRole.MEMBER);
    grant(library, memberWithAccess, AssetRole.VIEWER);

    UUID memberWithoutAccess = createUser();
    addMember(space, memberWithoutAccess, SpaceRole.MEMBER);

    SpaceLibraryLinks seenByMemberWithAccess =
        associationService.listForSpace(space, currentUserOf(memberWithAccess));
    SpaceLibraryLinks seenByMemberWithoutAccess =
        associationService.listForSpace(space, currentUserOf(memberWithoutAccess));

    assertThat(seenByMemberWithAccess.items())
        .extracting(link -> link.association().getLibraryId())
        .containsExactly(library);
    // #706 review, finding 2: a plain MEMBER with no readable association gets an empty items
    // list, but hasAssociations still reports the true, unfiltered state of the space - the
    // frontend needs both to tell "no curation" apart from "curated, nothing readable".
    assertThat(seenByMemberWithoutAccess.items()).isEmpty();
    assertThat(seenByMemberWithoutAccess.hasAssociations()).isTrue();
  }

  @Test
  void aSpaceWithNoAssociationsAtAllReportsHasAssociationsFalse() {
    UUID member = createUser();
    UUID space = createSpace(member, SpaceRole.ADMIN);

    SpaceLibraryLinks response = associationService.listForSpace(space, currentUserOf(member));

    assertThat(response.items()).isEmpty();
    assertThat(response.hasAssociations()).isFalse();
  }

  // #706 review, finding 5: a CURATOR/ADMIN/owner sees every association, including one they
  // cannot themselves read, so they can also detach it - unlike a plain MEMBER's filtered view.
  @Test
  void aSpaceAdminSeesAnAssociationTheyCannotThemselvesReadWithoutItsName() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(library, curator, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);
    associationService.associate(space, library, currentUserOf(curator));

    UUID otherAdmin = createUser();
    addMember(space, otherAdmin, SpaceRole.ADMIN);

    SpaceLibraryLinks seenByOtherAdmin =
        associationService.listForSpace(space, currentUserOf(otherAdmin));

    assertThat(seenByOtherAdmin.items()).hasSize(1);
    SpaceLibraryLink entry = seenByOtherAdmin.items().get(0);
    assertThat(entry.association().getLibraryId()).isEqualTo(library);
    assertThat(entry.readableByCaller()).isFalse();
    assertThat(entry.libraryName()).isNull();
  }

  @Test
  void ownerCanDetachAnAssociationCreatedBySomeoneElse() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(library, curator, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);
    associationService.associate(space, library, currentUserOf(curator));

    // The owner is not even a member of this space - detach still succeeds unilaterally.
    associationService.detach(space, library, currentUserOf(owner));

    assertThat(associationRepository.existsBySpaceIdAndLibraryId(space, library)).isFalse();
  }

  @Test
  void ordinaryCuratorCannotDetachAnotherLibrarysAssociationTheyDoNotManage() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(library, curator, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);
    associationService.associate(space, library, currentUserOf(curator));

    UUID stranger = createUser();

    assertThatThrownBy(() -> associationService.detach(space, library, currentUserOf(stranger)))
        .isInstanceOf(AccessDeniedException.class);
  }

  // #706 review, finding 7b: a plain MEMBER (neither CURATOR/ADMIN/owner of the space nor MANAGER
  // of the library) must not be able to detach an association.
  @Test
  void plainMemberCannotDetachAnAssociation() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    associationService.associate(space, library, currentUserOf(owner));

    UUID plainMember = createUser();
    addMember(space, plainMember, SpaceRole.MEMBER);

    assertThatThrownBy(() -> associationService.detach(space, library, currentUserOf(plainMember)))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(associationRepository.existsBySpaceIdAndLibraryId(space, library)).isTrue();
  }

  // #706 review, "Selbstbenachrichtigung": the curator who creates the association must never
  // receive their own owner notification, even when they are also (part of) the library's owner.
  @Test
  void theTriggeringUserIsNeverNotifiedOfTheirOwnAssociation() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    UUID memberWithoutAccess = createUser();
    addMember(space, memberWithoutAccess, SpaceRole.MEMBER);

    // The owner themselves creates the association (they are ADMIN of their own space) - a mixed
    // audience (memberWithoutAccess cannot read the library), but the owner is also the trigger.
    associationService.associate(space, library, currentUserOf(owner));

    assertThat(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(owner)).isEmpty();
  }

  @Test
  void ownerIsNotifiedWhenNotEveryMemberCanReadTheAssociatedLibrary() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(library, curator, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);
    UUID memberWithoutAccess = createUser();
    addMember(space, memberWithoutAccess, SpaceRole.MEMBER);

    associationService.associate(space, library, currentUserOf(curator));

    List<Notification> notifications =
        notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(owner);
    assertThat(notifications).hasSize(1);
    assertThat(notifications.get(0).getObjectId()).isEqualTo(library);
  }

  @Test
  void ownerIsNotNotifiedWhenEveryMemberCanAlreadyReadTheAssociatedLibrary() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(library, curator, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);

    associationService.associate(space, library, currentUserOf(curator));

    assertThat(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(owner)).isEmpty();
  }

  @Test
  void ownerSeesEveryAssociationAcrossSpacesRegardlessOfOwnMembership() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(library, curator, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);
    associationService.associate(space, library, currentUserOf(curator));

    List<LibrarySpaceLink> ownerView =
        associationService.listForLibrary(library, currentUserOf(owner));

    assertThat(ownerView)
        .extracting(link -> link.association().getSpaceId())
        .containsExactly(space);
  }

  @Test
  void deletingTheSpaceRemovesAssociationsButLeavesTheLibraryUntouched() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    associationService.associate(space, library, currentUserOf(owner));

    spaceRepository.deleteById(space);

    assertThat(associationRepository.existsBySpaceIdAndLibraryId(space, library)).isFalse();
    assertThat(libraryRepository.existsById(library)).isTrue();
  }
}
