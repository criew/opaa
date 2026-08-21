package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.LibrarySpaceAssociationResponse;
import io.opaa.api.dto.SpaceLibraryAssociationResponse;
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

    SpaceLibraryAssociationResponse response =
        associationService.associate(space, library, owner, false);

    assertThat(response.getLibraryId()).isEqualTo(library);
  }

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
                    .isEqualTo(HttpStatus.FORBIDDEN));
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

    List<SpaceLibraryAssociationResponse> seenByMemberWithAccess =
        associationService.listForSpace(space, memberWithAccess, false);
    List<SpaceLibraryAssociationResponse> seenByMemberWithoutAccess =
        associationService.listForSpace(space, memberWithoutAccess, false);

    assertThat(seenByMemberWithAccess)
        .extracting(SpaceLibraryAssociationResponse::getLibraryId)
        .containsExactly(library);
    assertThat(seenByMemberWithoutAccess).isEmpty();
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

    List<LibrarySpaceAssociationResponse> ownerView =
        associationService.listForLibrary(library, owner, false);

    assertThat(ownerView)
        .extracting(LibrarySpaceAssociationResponse::getSpaceId)
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
