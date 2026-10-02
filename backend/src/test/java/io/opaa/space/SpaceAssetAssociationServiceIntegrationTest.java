package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.notification.Notification;
import io.opaa.notification.NotificationRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetType;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptLibraryRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied - same
 * pattern as {@link SpaceServiceIntegrationTest} (#288).
 */
@OpaaIntegrationTest
class SpaceAssetAssociationServiceIntegrationTest {

  @Autowired private SpaceAssetAssociationService associationService;
  @Autowired private SpaceService spaceService;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  @Autowired private PromptLibraryRepository promptLibraryRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService libraryAccessService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private SpaceAssetAssociationRepository associationRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;

  private UUID organizationA;

  // Every row this class writes belongs to the throwaway organization created here, so tearDown()
  // removes exactly that organization and everything in it. No cleanup in this hook: a freshly
  // created organization cannot hold rows of an earlier test method.
  @BeforeEach
  void setUp() {
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationA);
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationA);
    return userRepository.save(user).getId();
  }

  private UUID createSpace(UUID ownerId, SpaceRole ownerRole) {
    return createSpace(ownerId, ownerRole, SpaceVisibility.PRIVATE);
  }

  private UUID createSpace(UUID ownerId, SpaceRole ownerRole, SpaceVisibility visibility) {
    Space space = new Space("Fachbereich", null, false, visibility, ownerId, organizationA);
    space.addMembership(SpaceMembership.ofUser(ownerId, ownerRole, organizationA));
    return spaceRepository.save(space).getId();
  }

  private void addMember(UUID spaceId, UUID userId, SpaceRole role) {
    Space space = spaceRepository.findByIdWithMemberships(spaceId).orElseThrow();
    space.addMembership(SpaceMembership.ofUser(userId, role, organizationA));
    spaceRepository.save(space);
  }

  private UUID createLibrary(UUID ownerId) {
    return createLibrary(ownerId, "Bibliothek");
  }

  private UUID createLibrary(UUID ownerId, String name) {
    KnowledgeLibrary library = KnowledgeLibrary.ownedByUser(organizationA, name, null, ownerId);
    return libraryRepository.save(library).getId();
  }

  private UUID createPromptLibrary(UUID ownerId) {
    return createPromptLibrary(ownerId, "Vorlagen");
  }

  private UUID createPromptLibrary(UUID ownerId, String name) {
    return promptLibraryRepository
        .save(PromptLibrary.ownedByUser(organizationA, name, null, ownerId))
        .getId();
  }

  private void grant(UUID libraryId, UUID userId, AssetRole role) {
    grant(KnowledgeLibrary.ASSET_TYPE, libraryId, userId, role);
  }

  private void grant(AssetType assetType, UUID assetId, UUID userId, AssetRole role) {
    grantRepository.save(
        AssetGrant.forUser(assetType, assetId, organizationA, userId, role, null, userId));
  }

  private CurrentUser currentUserOf(UUID userId) {
    return currentUserOf(userId, false);
  }

  private CurrentUser currentUserOf(UUID userId, boolean systemAdmin) {
    return CurrentUser.of(
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
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));
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

    SpaceAssetLink response =
        associationService.associate(
            space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    assertThat(response.association().getAssetId()).isEqualTo(library);
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

    assertThatThrownBy(
            () ->
                associationService.associate(
                    space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(curator)))
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

    assertThatThrownBy(
            () ->
                associationService.associate(
                    space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(member)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void twoMembersWithDifferentGrantsSeeDifferentAssociatedLibraryLists() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    UUID memberWithAccess = createUser();
    addMember(space, memberWithAccess, SpaceRole.MEMBER);
    grant(library, memberWithAccess, AssetRole.VIEWER);

    UUID memberWithoutAccess = createUser();
    addMember(space, memberWithoutAccess, SpaceRole.MEMBER);

    SpaceAssetLinks seenByMemberWithAccess =
        associationService.listForSpace(space, currentUserOf(memberWithAccess));
    SpaceAssetLinks seenByMemberWithoutAccess =
        associationService.listForSpace(space, currentUserOf(memberWithoutAccess));

    assertThat(seenByMemberWithAccess.items())
        .extracting(link -> link.association().getAssetId())
        .containsExactly(library);
    // A plain MEMBER with no readable association gets an empty items list, but the flags still
    // report the unfiltered state of the space - the frontend needs them to tell "no knowledge
    // associated" apart from "associated, nothing readable".
    assertThat(seenByMemberWithoutAccess.items()).isEmpty();
    assertThat(seenByMemberWithoutAccess.hasAssociations()).isTrue();
    assertThat(seenByMemberWithoutAccess.hasKnowledge()).isTrue();
    assertThat(seenByMemberWithoutAccess.hasReadableKnowledge()).isFalse();
    assertThat(seenByMemberWithAccess.hasReadableKnowledge()).isTrue();
  }

  @Test
  void aSpaceWithNoAssociationsAtAllReportsHasAssociationsFalse() {
    UUID member = createUser();
    UUID space = createSpace(member, SpaceRole.ADMIN);

    SpaceAssetLinks response = associationService.listForSpace(space, currentUserOf(member));

    assertThat(response.items()).isEmpty();
    assertThat(response.hasAssociations()).isFalse();
    assertThat(response.hasKnowledge()).isFalse();
    assertThat(response.hasReadableKnowledge()).isFalse();
  }

  /**
   * ADR-0039, Entscheidung 2: no role in the space - not ADMIN, CURATOR, the owner or a system
   * admin - learns name, id or number of an association it cannot read. The one disclosure is the
   * count-free flag.
   */
  @Test
  void noRoleInTheSpaceSeesAnAssociationItCannotReadButEveryRoleGetsTheHint() {
    UUID owner = createUser();
    UUID hidden = createLibrary(owner);
    grant(hidden, owner, AssetRole.OWNER);
    UUID shared = createLibrary(owner);
    grant(shared, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    Map<String, CurrentUser> callers = new LinkedHashMap<>();
    for (SpaceRole role : List.of(SpaceRole.ADMIN, SpaceRole.CURATOR, SpaceRole.MEMBER)) {
      UUID caller = createUser();
      addMember(space, caller, role);
      grant(shared, caller, AssetRole.VIEWER);
      callers.put(role.name(), currentUserOf(caller));
    }
    UUID systemAdmin = createUser();
    addMember(space, systemAdmin, SpaceRole.ADMIN);
    grant(shared, systemAdmin, AssetRole.VIEWER);
    callers.put("SYSTEM_ADMIN", currentUserOf(systemAdmin, true));
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, hidden, currentUserOf(owner));
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, shared, currentUserOf(owner));

    callers.forEach(
        (label, caller) -> {
          SpaceAssetLinks view = associationService.listForSpace(space, caller);
          assertThat(view.items())
              .as(label)
              .extracting(link -> link.association().getAssetId())
              .containsExactly(shared);
          assertThat(view.hasUnreadableAssociations()).as(label).isTrue();
          assertThat(view.hasAssociations()).as(label).isTrue();
        });
    SpaceAssetLinks ownerView = associationService.listForSpace(space, currentUserOf(owner));
    assertThat(ownerView.items()).hasSize(2);
    assertThat(ownerView.hasUnreadableAssociations()).isFalse();
  }

  @Test
  void theOverviewFigureCountsOnlyWhatTheCallerMayReadAlsoForACurator() {
    UUID owner = createUser();
    UUID hidden = createLibrary(owner);
    grant(hidden, owner, AssetRole.OWNER);
    UUID shared = createLibrary(owner);
    grant(shared, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    UUID curator = createUser();
    addMember(space, curator, SpaceRole.CURATOR);
    grant(shared, curator, AssetRole.VIEWER);
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, hidden, currentUserOf(owner));
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, shared, currentUserOf(owner));

    Space loaded = spaceRepository.findByIdWithMemberships(space).orElseThrow();

    assertThat(associationService.countVisibleBySpace(List.of(loaded), currentUserOf(curator)))
        .containsEntry(space, 1L);
    assertThat(
            associationService.countVisibleBySpace(List.of(loaded), currentUserOf(curator, true)))
        .containsEntry(space, 1L);
  }

  @Test
  void aSpaceWithCreationAssetsOfEveryTypeIsCreatedInOneCall() {
    UUID creator = createUser();
    UUID library = createLibrary(creator);
    grant(library, creator, AssetRole.OWNER);
    UUID prompts = createPromptLibrary(creator);
    grant(PromptLibrary.ASSET_TYPE, prompts, creator, AssetRole.OWNER);

    Space created =
        spaceService.createSpace(
            new SpaceCreation(
                "Widerspruchsstelle",
                null,
                null,
                null,
                List.of(),
                List.of(
                    new SpaceAssetSeed(KnowledgeLibrary.ASSET_TYPE, library),
                    new SpaceAssetSeed(PromptLibrary.ASSET_TYPE, prompts))),
            currentUserOf(creator));

    SpaceAssetLinks view = associationService.listForSpace(created.getId(), currentUserOf(creator));
    assertThat(view.items())
        .extracting(SpaceAssetLink::assetType)
        .containsExactlyInAnyOrder(KnowledgeLibrary.ASSET_TYPE, PromptLibrary.ASSET_TYPE);
  }

  /**
   * Space, members, assets, audit and notifications are created together or not at all: an asset
   * the creator cannot read, last in the request, leaves none of them behind.
   */
  @Test
  void anAssetTheCreatorCannotReadRollsTheWholeCreationBack() {
    UUID owner = createUser();
    UUID shared = createLibrary(owner, "Geteilt");
    grant(shared, owner, AssetRole.OWNER);
    UUID foreign = createPromptLibrary(owner);
    grant(PromptLibrary.ASSET_TYPE, foreign, owner, AssetRole.OWNER);
    UUID creator = createUser();
    grant(shared, creator, AssetRole.VIEWER);
    UUID member = createUser();

    assertThatThrownBy(
            () ->
                spaceService.createSpace(
                    new SpaceCreation(
                        "Ohne Zugriff",
                        null,
                        null,
                        null,
                        List.of(new SpaceMemberSeed(member, SpaceRole.MEMBER)),
                        List.of(
                            new SpaceAssetSeed(KnowledgeLibrary.ASSET_TYPE, shared),
                            new SpaceAssetSeed(PromptLibrary.ASSET_TYPE, foreign))),
                    currentUserOf(creator)))
        .isInstanceOf(NotFoundException.class);

    assertThat(spaceRepository.findDistinctByMembershipsUserIdWithMemberships(creator)).isEmpty();
    assertThat(spaceRepository.findDistinctByMembershipsUserIdWithMemberships(member)).isEmpty();
    assertThat(associationRepository.findByAssetIdOrderByCreatedAtAsc(shared)).isEmpty();
    assertThat(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(owner)).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ? AND event_type IN"
                    + " ('SPACE_CREATED', 'SPACE_MEMBER_ADDED', 'ASSET_SHARED_TO_SPACE')",
                Long.class,
                organizationA))
        .isZero();
  }

  /**
   * One notification per owner for the whole creation, naming every asset of theirs that now stands
   * in a space not every member may read - whatever the asset types.
   */
  @Test
  void creatingASpaceWithSeveralAssetsNotifiesEachOwnerOnceForAllOfThem() {
    UUID owner = createUser();
    UUID library = createLibrary(owner, "Rechtsquellen");
    grant(library, owner, AssetRole.OWNER);
    UUID prompts = createPromptLibrary(owner, "Bescheidvorlagen");
    grant(PromptLibrary.ASSET_TYPE, prompts, owner, AssetRole.OWNER);
    UUID creator = createUser();
    grant(library, creator, AssetRole.VIEWER);
    grant(PromptLibrary.ASSET_TYPE, prompts, creator, AssetRole.VIEWER);
    UUID memberWithoutAccess = createUser();

    Space created =
        spaceService.createSpace(
            new SpaceCreation(
                "Widerspruchsstelle",
                null,
                null,
                null,
                List.of(new SpaceMemberSeed(memberWithoutAccess, SpaceRole.MEMBER)),
                List.of(
                    new SpaceAssetSeed(KnowledgeLibrary.ASSET_TYPE, library),
                    new SpaceAssetSeed(PromptLibrary.ASSET_TYPE, prompts))),
            currentUserOf(creator));

    assertThat(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(owner))
        .singleElement()
        .satisfies(
            notification -> {
              assertThat(notification.getType())
                  .isEqualTo(NotificationType.ASSET_ASSOCIATED_TO_MIXED_SPACE);
              assertThat(notification.getObjectType()).isEqualTo(AuditObjectType.SPACE);
              assertThat(notification.getObjectId()).isEqualTo(created.getId());
              assertThat(notification.getBody())
                  .contains("Rechtsquellen", "Bescheidvorlagen", "Widerspruchsstelle");
            });
    assertThat(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(creator)).isEmpty();
  }

  @Test
  void ownerOfAPromptLibraryIsNotifiedLikeTheOwnerOfAKnowledgeLibrary() {
    UUID owner = createUser();
    UUID prompts = createPromptLibrary(owner);
    grant(PromptLibrary.ASSET_TYPE, prompts, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(PromptLibrary.ASSET_TYPE, prompts, curator, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);
    addMember(space, createUser(), SpaceRole.MEMBER);

    associationService.associate(space, PromptLibrary.ASSET_TYPE, prompts, currentUserOf(curator));

    assertThat(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(owner))
        .singleElement()
        .satisfies(
            notification -> {
              assertThat(notification.getType())
                  .isEqualTo(NotificationType.ASSET_ASSOCIATED_TO_MIXED_SPACE);
              assertThat(notification.getObjectId()).isEqualTo(prompts);
            });
  }

  @Test
  void ownerCanDetachAnAssociationCreatedBySomeoneElse() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(library, curator, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);
    associationService.associate(
        space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(curator));

    // The owner is not even a member of this space - detach still succeeds unilaterally.
    associationService.detach(space, library, currentUserOf(owner));

    assertThat(associationRepository.existsBySpaceIdAndAssetId(space, library)).isFalse();
  }

  @Test
  void aReaderWhoIsNoCuratorCannotDetachAnAssociation() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID curator = createUser();
    grant(library, curator, AssetRole.VIEWER);
    UUID stranger = createUser();
    grant(library, stranger, AssetRole.VIEWER);
    UUID space = createSpace(curator, SpaceRole.ADMIN);
    associationService.associate(
        space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(curator));

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
    UUID plainMember = createUser();
    grant(library, plainMember, AssetRole.VIEWER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    addMember(space, plainMember, SpaceRole.MEMBER);
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    assertThatThrownBy(() -> associationService.detach(space, library, currentUserOf(plainMember)))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(associationRepository.existsBySpaceIdAndAssetId(space, library)).isTrue();
  }

  /**
   * ADR-0039, Entscheidung 2: detaching an asset one may not read answers like an unknown asset, in
   * every role of the space - a curator cannot detach what they cannot see, and nobody learns that
   * the asset exists.
   */
  @Test
  void detachingAnUnreadableAssetAnswersLikeAnUnknownOneInEveryRole() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    Map<SpaceRole, UUID> callers = new LinkedHashMap<>();
    for (SpaceRole role : List.of(SpaceRole.ADMIN, SpaceRole.CURATOR, SpaceRole.MEMBER)) {
      UUID caller = createUser();
      addMember(space, caller, role);
      callers.put(role, caller);
    }
    UUID outsider = createUser();
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    String unknownMessage =
        catchThrowable(
                () -> associationService.detach(space, UUID.randomUUID(), currentUserOf(owner)))
            .getMessage();
    List<UUID> everyone = new java.util.ArrayList<>(callers.values());
    everyone.add(outsider);
    for (UUID caller : everyone) {
      assertThatThrownBy(() -> associationService.detach(space, library, currentUserOf(caller)))
          .isInstanceOf(NotFoundException.class)
          .hasMessage(unknownMessage);
    }
    assertThat(associationRepository.existsBySpaceIdAndAssetId(space, library)).isTrue();
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
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

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

    associationService.associate(
        space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(curator));

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

    associationService.associate(
        space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(curator));

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
    associationService.associate(
        space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(curator));

    AssetSpaceLinks ownerView =
        associationService.listForAsset(KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    assertThat(ownerView.items())
        .extracting(link -> link.association().getSpaceId())
        .containsExactly(space);
    assertThat(ownerView.items())
        .singleElement()
        .extracting(AssetSpaceLink::managementDetail)
        .isEqualTo(true);
    assertThat(ownerView.hiddenCount()).isZero();
  }

  /**
   * #1939: die Zuordnungen stehen im schreibgeschützten Abschnitt des Reiters „Freigaben" - ein
   * VIEWER erfährt den Space-Namen, aber weder den Lesekreis noch wer die Zuordnung angelegt hat.
   */
  @Test
  void viewerSeesTheSpaceNameButNoManagementDetailOfAnAssociation() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID reader = createUser();
    grant(library, reader, AssetRole.VIEWER);
    // DISCOVERABLE: der Space steht ohnehin im Verzeichnis, sein Name ist keine Preisgabe.
    UUID space = createSpace(owner, SpaceRole.ADMIN, SpaceVisibility.DISCOVERABLE);
    // A member without their own read access - exactly what narrowerReaderCircle would report.
    addMember(space, createUser(), SpaceRole.MEMBER);
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    AssetSpaceLinks readerView =
        associationService.listForAsset(
            KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(reader));

    assertThat(readerView.hiddenCount()).isZero();
    assertThat(readerView.items())
        .singleElement()
        .satisfies(
            link -> {
              assertThat(link.association().getSpaceId()).isEqualTo(space);
              assertThat(link.spaceName()).isEqualTo("Fachbereich");
              assertThat(link.managementDetail()).isFalse();
              assertThat(link.narrowerReaderCircle()).isFalse();
              assertThat(link.createdByDisplayName()).isNull();
            });

    assertThat(
            associationService
                .listForAsset(KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner))
                .items())
        .singleElement()
        .satisfies(
            link -> {
              assertThat(link.managementDetail()).isTrue();
              assertThat(link.narrowerReaderCircle()).isTrue();
            });
  }

  /**
   * #1939: Ein PRIVATE-Space verspricht, dass nur seine Mitglieder von ihm wissen
   * (docs/features/spaces-and-assets.md, „Space-Sichtbarkeit"). Unterhalb von MANAGER wird eine
   * Zuordnung dorthin deshalb nicht benannt, sondern nur gezählt - auch für einen EDITOR, der die
   * Schwelle `canManage` ebenfalls nicht erreicht.
   */
  @Test
  void aPrivateSpaceIsOnlyCountedBelowManagerAndNamedFromManagerOn() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN, SpaceVisibility.PRIVATE);
    // Jede Berechtigung steht, bevor ein Dienstaufruf den Grant-Zwischenspeicher der Bibliothek
    // füllt - dieser Test schreibt sie am Dienst vorbei direkt ins Repository.
    Map<AssetRole, UUID> callers = new LinkedHashMap<>();
    for (AssetRole role : List.of(AssetRole.VIEWER, AssetRole.EDITOR, AssetRole.MANAGER)) {
      UUID caller = createUser();
      grant(library, caller, role);
      callers.put(role, caller);
    }
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    for (AssetRole role : List.of(AssetRole.VIEWER, AssetRole.EDITOR)) {
      AssetSpaceLinks view =
          associationService.listForAsset(
              KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(callers.get(role)));

      assertThat(view.items()).as("role %s", role).isEmpty();
      assertThat(view.hiddenCount()).as("role %s", role).isEqualTo(1);
    }

    UUID manager = callers.get(AssetRole.MANAGER);
    AssetSpaceLinks managerView =
        associationService.listForAsset(
            KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(manager));

    assertThat(managerView.hiddenCount()).isZero();
    assertThat(managerView.items())
        .singleElement()
        .extracting(AssetSpaceLink::spaceName)
        .isEqualTo("Fachbereich");
  }

  /** Wer im PRIVATE-Space Mitglied ist, weiß ohnehin von ihm - für ihn ist nichts verborgen. */
  @Test
  void aReaderWhoBelongsToThePrivateSpaceSeesItByName() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID reader = createUser();
    grant(library, reader, AssetRole.VIEWER);
    UUID space = createSpace(owner, SpaceRole.ADMIN, SpaceVisibility.PRIVATE);
    addMember(space, reader, SpaceRole.MEMBER);
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    AssetSpaceLinks readerView =
        associationService.listForAsset(
            KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(reader));

    assertThat(readerView.hiddenCount()).isZero();
    assertThat(readerView.items())
        .singleElement()
        .extracting(AssetSpaceLink::spaceName)
        .isEqualTo("Fachbereich");
  }

  /** Below VIEWER the asset is not there at all - the same 404 as for an unknown id (#436). */
  @Test
  void aPersonWithoutAnyRoleOnTheAssetStillGetsNotFoundForItsAssociations() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID outsider = createUser();

    assertThatThrownBy(
            () ->
                associationService.listForAsset(
                    KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(outsider)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void deletingTheSpaceRemovesAssociationsButLeavesTheLibraryUntouched() {
    UUID owner = createUser();
    UUID library = createLibrary(owner);
    grant(library, owner, AssetRole.OWNER);
    UUID space = createSpace(owner, SpaceRole.ADMIN);
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, currentUserOf(owner));

    spaceRepository.deleteById(space);

    assertThat(associationRepository.existsBySpaceIdAndAssetId(space, library)).isFalse();
    assertThat(libraryRepository.existsById(library)).isTrue();
  }
}
