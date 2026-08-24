package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.ChatRepository;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied - same
 * pattern as {@link SpaceServiceIntegrationTest} (#288).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
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

  @Test
  void associatingALibraryChangesNoOnesEffectivePermissions() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID member = createUser();
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    addMember(space, member, SpaceRole.CURATOR);

    Set<UUID> readableBefore = libraryAccessService.readableLibraryIds(member, organizationA);
    associationService.associate(space, library, owner, false);
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

    SpaceLibraryLink response = associationService.associate(space, library, owner, false);

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

    assertThatThrownBy(() -> associationService.associate(space, library, curator, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
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

    assertThatThrownBy(() -> associationService.associate(space, library, member, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void twoMembersWithDifferentGrantsSeeDifferentAssociatedLibraryLists() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    associationService.associate(space, library, owner, false);

    UUID memberWithAccess = createUser();
    addMember(space, memberWithAccess, SpaceRole.MEMBER);
    grant(library, memberWithAccess, AssetRole.VIEWER);

    UUID memberWithoutAccess = createUser();
    addMember(space, memberWithoutAccess, SpaceRole.MEMBER);

    SpaceLibraryLinks seenByMemberWithAccess =
        associationService.listForSpace(space, memberWithAccess, false);
    SpaceLibraryLinks seenByMemberWithoutAccess =
        associationService.listForSpace(space, memberWithoutAccess, false);

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

    SpaceLibraryLinks response = associationService.listForSpace(space, member, false);

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
    associationService.associate(space, library, curator, false);

    UUID otherAdmin = createUser();
    addMember(space, otherAdmin, SpaceRole.ADMIN);

    SpaceLibraryLinks seenByOtherAdmin = associationService.listForSpace(space, otherAdmin, false);

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
    associationService.associate(space, library, curator, false);

    // The owner is not even a member of this space - detach still succeeds unilaterally.
    associationService.detach(space, library, owner, false);

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
    associationService.associate(space, library, curator, false);

    UUID stranger = createUser();

    assertThatThrownBy(() -> associationService.detach(space, library, stranger, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  // #706 review, finding 7b: a plain MEMBER (neither CURATOR/ADMIN/owner of the space nor MANAGER
  // of the library) must not be able to detach an association.
  @Test
  void plainMemberCannotDetachAnAssociation() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    associationService.associate(space, library, owner, false);

    UUID plainMember = createUser();
    addMember(space, plainMember, SpaceRole.MEMBER);

    assertThatThrownBy(() -> associationService.detach(space, library, plainMember, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

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
    associationService.associate(space, library, owner, false);

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

    associationService.associate(space, library, curator, false);

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

    associationService.associate(space, library, curator, false);

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
    associationService.associate(space, library, curator, false);

    List<LibrarySpaceLink> ownerView = associationService.listForLibrary(library, owner, false);

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
    associationService.associate(space, library, owner, false);

    spaceRepository.deleteById(space);

    assertThat(associationRepository.existsBySpaceIdAndLibraryId(space, library)).isFalse();
    assertThat(libraryRepository.existsById(library)).isTrue();
  }
}
