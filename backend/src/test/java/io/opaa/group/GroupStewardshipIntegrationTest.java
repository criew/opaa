package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.library.AssetGrantService;
import io.opaa.library.AssetGrantUpsert;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.notification.Notification;
import io.opaa.notification.NotificationRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.CapabilityGrant;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.permission.GroupMembershipHistoryRepository;
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
 * The acceptance criteria of #1814 (ADR-0036, Entscheidungen 4 and 9) against the real, versioned
 * schema: who may maintain an internal group, who may see it as a grant subject, and what a change
 * of membership leaves behind.
 *
 * <p>Runs against a real Postgres with Liquibase applied rather than Hibernate DDL, for the same
 * reason {@code GroupServiceIntegrationTest} does: the composite foreign keys of {@code
 * group_stewards} and the {@code released_for_use} default only exist in the versioned changelog.
 */
@OpaaIntegrationTest
class GroupStewardshipIntegrationTest {

  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private CapabilityGrantRepository capabilityGrantRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private AssetGrantService assetGrantService;
  @Autowired private AssetGrantRepository assetGrantRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID providerId;
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdCapabilityGrantIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdCapabilityGrantIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org 1814")).getId();
    providerId = ProviderFixtures.tokenProvider(providerRepository).getId();
  }

  @AfterEach
  void tearDown() {
    assetGrantRepository.deleteAll(
        assetGrantRepository.findAll().stream()
            .filter(grant -> grant.getOrganizationId().equals(organizationId))
            .toList());
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream()
            .filter(library -> library.getOrganizationId().equals(organizationId))
            .toList());
    capabilityGrantRepository.deleteAllById(createdCapabilityGrantIds);
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    groupRepository.deleteAll(
        groupRepository.findAll().stream()
            .filter(group -> group.getOrganizationId().equals(organizationId))
            .toList());
    // notifications cascade with their recipient (fk_notifications_recipient_organization).
    userRepository.deleteAllById(createdUserIds);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
    if (providerId != null) {
      providerRepository.deleteById(providerId);
      providerId = null;
    }
  }

  // -------------------------------------------------------------------------------------------
  // Anlegen und Pflegen ohne Systemrolle
  // -------------------------------------------------------------------------------------------

  @Test
  void anAccountWithTheCapabilityCreatesAGroupBecomesItsStewardAndMaintainsItsMembers() {
    CurrentUser creator = grantInternalGroupCapability(regularUser());
    UUID member = regularUser();

    GroupDetail created = groupService.createGroup(new GroupCreation("Projektteam", null), creator);

    assertThat(created.stewards())
        .extracting(view -> view.steward().getUserId())
        .containsExactly(creator.id());
    assertThat(
            groupService
                .addMember(created.group().getId(), member, creator)
                .membership()
                .getUserId())
        .isEqualTo(member);
    assertThat(groupService.listMembers(created.group().getId(), creator))
        .extracting(view -> view.membership().getUserId())
        .containsExactly(member);
    assertThat(groupService.listStewardedGroups(creator))
        .extracting(overview -> overview.group().getId())
        .containsExactly(created.group().getId());
  }

  /** A new group is not released: "Vorgabe nicht freigegeben" (ADR-0036, Entscheidung 9). */
  @Test
  void aNewlyCreatedGroupIsNotReleasedForUse() {
    CurrentUser creator = grantInternalGroupCapability(regularUser());

    GroupDetail created = groupService.createGroup(new GroupCreation("Projektteam", null), creator);

    assertThat(created.group().isReleasedForUse()).isFalse();
    assertThat(created.group().isSelectableAsSubject()).isFalse();
    assertThat(created.group().isProtectedGroup()).isFalse();
  }

  @Test
  void anAccountWithoutTheCapabilityCannotCreateAGroup() {
    CurrentUser stranger = currentUserOf(regularUser());

    assertThatThrownBy(() -> groupService.createGroup(new GroupCreation("Team", null), stranger))
        .isInstanceOf(AccessDeniedException.class);
  }

  /**
   * A non-steward gets the answer an unknown group gets - not a 403, which would confirm that a
   * group with this id exists (ADR-0036, Entscheidung 4).
   */
  @Test
  void aCallerWhoIsNoStewardGetsTheSameAnswerAsForAnUnknownGroup() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    CurrentUser stranger = currentUserOf(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    UUID someone = regularUser();

    assertThatThrownBy(() -> groupService.getGroup(groupId, stranger))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Gruppe nicht gefunden");
    assertThatThrownBy(
            () -> groupService.updateGroup(groupId, new GroupUpdate("X", null), stranger))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> groupService.addMember(groupId, someone, stranger))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> groupService.appointSteward(groupId, someone, stranger))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> groupService.setRelease(groupId, true, stranger))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> groupService.deleteGroup(groupId, stranger))
        .isInstanceOf(NotFoundException.class);
  }

  /** A mere member is no steward: being reached by a group is not maintaining it. */
  @Test
  void aMemberIsNoSteward() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    CurrentUser member = currentUserOf(regularUser());
    groupService.addMember(groupId, member.id(), steward);

    assertThatThrownBy(() -> groupService.getGroup(groupId, member))
        .isInstanceOf(NotFoundException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Verantwortliche
  // -------------------------------------------------------------------------------------------

  @Test
  void theLastStewardCannotStepDownButASystemAdministratorMayReleaseThem() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();

    assertThatThrownBy(() -> groupService.dismissSteward(groupId, steward.id(), steward))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("letzte verantwortliche Person");

    groupService.dismissSteward(groupId, steward.id(), currentUserOf(systemAdmin()));

    assertThat(stewardRepository.countByGroupId(groupId)).isZero();
  }

  /**
   * Handing responsibility over is a deliberate step: the successor is appointed first, and only
   * then does the previous steward leave (ADR-0036, Entscheidung 4).
   */
  @Test
  void responsibilityIsHandedOverByAppointingTheSuccessorFirst() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    UUID successor = regularUser();

    groupService.appointSteward(groupId, successor, steward);
    groupService.dismissSteward(groupId, steward.id(), steward);

    assertThat(stewardRepository.findByGroupIdOrderByCreatedAtAsc(groupId))
        .extracting(GroupSteward::getUserId)
        .containsExactly(successor);
  }

  @Test
  void aPersonIsAppointedOnlyOnceAndOnlyFromTheOwnOrganization() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    UUID successor = regularUser();
    groupService.appointSteward(groupId, successor, steward);

    assertThatThrownBy(() -> groupService.appointSteward(groupId, successor, steward))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> groupService.appointSteward(groupId, UUID.randomUUID(), steward))
        .isInstanceOf(NotFoundException.class);
  }

  /**
   * Appointment and dismissal are audit events and no history rows (ADR-0036, Entscheidungen 4 and
   * 8) - responsibility carries no read access, so it says nothing about a given day.
   */
  @Test
  void appointmentAndDismissalAreAuditedAndLeaveNoHistoryRow() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    UUID successor = regularUser();

    groupService.appointSteward(groupId, successor, steward);
    groupService.dismissSteward(groupId, successor, steward);

    assertThat(auditCount(groupId, AuditEventType.GROUP_STEWARD_APPOINTED))
        .as("one for the creator, one for the successor")
        .isEqualTo(2);
    assertThat(auditCount(groupId, AuditEventType.GROUP_STEWARD_DISMISSED)).isEqualTo(1);
    assertThat(membershipHistoryRows(groupId))
        .as("responsibility is no membership and writes no rights-history row")
        .isZero();
  }

  // -------------------------------------------------------------------------------------------
  // Freigabe zur Verwendung und Schutzkennzeichen
  // -------------------------------------------------------------------------------------------

  /**
   * The acceptance criterion of ADR-0036, Entscheidung 9 that the selection list alone could not
   * carry: the id typed by hand takes the same path and gets the same 404.
   */
  @Test
  void anUnreleasedInternalGroupIsNoGrantTargetEvenWhenItsIdIsTypedByHand() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    CurrentUser manager = currentUserOf(regularUser());
    UUID libraryId = libraryManagedBy(manager);
    AssetGrantUpsert request =
        new AssetGrantUpsert(PermissionSubjectType.GROUP, groupId, AssetRole.VIEWER);

    assertThatThrownBy(() -> assetGrantService.upsertGrant(libraryId, request, manager))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Gruppe nicht gefunden");

    groupService.setRelease(groupId, true, steward);

    assertThat(
            assetGrantService.upsertGrant(libraryId, request, manager).grant().getSubjectGroupId())
        .isEqualTo(groupId);
  }

  /** Its own stewards and members keep seeing it - the rule hides it from third parties only. */
  @Test
  void anUnreleasedGroupStaysAGrantTargetForItsOwnMembersAndStewards() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    CurrentUser member = currentUserOf(regularUser());
    groupService.addMember(groupId, member.id(), steward);
    AssetGrantUpsert request =
        new AssetGrantUpsert(PermissionSubjectType.GROUP, groupId, AssetRole.VIEWER);

    assertThat(
            assetGrantService
                .upsertGrant(libraryManagedBy(member), request, member)
                .grant()
                .getSubjectGroupId())
        .isEqualTo(groupId);
    assertThat(
            assetGrantService
                .upsertGrant(libraryManagedBy(steward), request, steward)
                .grant()
                .getSubjectGroupId())
        .isEqualTo(groupId);
  }

  @Test
  void theReleaseIsAuditedOnlyWhenItActuallyChanges() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();

    groupService.setRelease(groupId, true, steward);
    groupService.setRelease(groupId, true, steward);
    groupService.setRelease(groupId, false, steward);

    assertThat(groupRepository.findById(groupId).orElseThrow().isReleasedForUse()).isFalse();
    assertThat(auditCount(groupId, AuditEventType.GROUP_RELEASE_CHANGED)).isEqualTo(2);
  }

  /**
   * The one thing a system administrator may not do to a group they do not steward (ADR-0036,
   * Entscheidung 9; Personalrat A3) - otherwise the protection would be the administration's.
   */
  @Test
  void onlyAStewardSetsTheProtectionMarkNotTheAdministration() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Personalrat", null), steward).group().getId();
    CurrentUser admin = currentUserOf(systemAdmin());

    assertThatThrownBy(() -> groupService.setProtection(groupId, true, admin))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Verantwortlichen dieser Gruppe selbst");

    groupService.setProtection(groupId, true, steward);

    assertThat(groupRepository.findById(groupId).orElseThrow().isProtectedGroup()).isTrue();
    assertThat(auditCount(groupId, AuditEventType.GROUP_PROTECTION_CHANGED)).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Anbietergruppen, Benachrichtigung, Akteur
  // -------------------------------------------------------------------------------------------

  @Test
  void aStewardCannotChangeAProviderGroup() {
    CurrentUser steward = currentUserOf(regularUser());
    Group providerGroup =
        groupRepository.save(
            new Group(
                organizationId,
                GroupKind.IDENTITY_PROVIDER,
                "Referat 50",
                null,
                providerId,
                "Referat 50",
                null,
                null));
    stewardRepository.save(
        new GroupSteward(providerGroup.getId(), steward.id(), organizationId, steward.id()));
    UUID someone = regularUser();

    assertThatThrownBy(
            () ->
                groupService.updateGroup(
                    providerGroup.getId(), new GroupUpdate("Anders", null), steward))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> groupService.addMember(providerGroup.getId(), someone, steward))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> groupService.setRelease(providerGroup.getId(), true, steward))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> groupService.setProtection(providerGroup.getId(), true, steward))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> groupService.appointSteward(providerGroup.getId(), someone, steward))
        .isInstanceOf(ValidationException.class);
  }

  /** In the application, never by mail (ADR-0036, Entscheidung 4; Personalrat A4). */
  @Test
  void beingTakenInAndBeingRemovedAreBothShownToThePersonConcerned() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    UUID member = regularUser();

    groupService.addMember(groupId, member, steward);
    groupService.removeMember(groupId, member, steward);

    assertThat(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(member))
        .extracting(Notification::getType)
        .containsExactlyInAnyOrder(
            NotificationType.GROUP_MEMBER_ADDED, NotificationType.GROUP_MEMBER_REMOVED);
    assertThat(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(steward.id()))
        .as("the steward acts, they are not the person concerned")
        .isEmpty();
  }

  /** The steward, not a system administrator, is the actor of the change they made. */
  @Test
  void aMembershipChangeNamesTheStewardAsItsActor() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    UUID member = regularUser();

    groupService.addMember(groupId, member, steward);

    assertThat(membershipHistoryRows(groupId)).isEqualTo(1);
    assertThat(membershipHistoryRepository.findByGroupIdAndUserIdAndValidToIsNull(groupId, member))
        .get()
        .satisfies(row -> assertThat(row.getActorUserId()).isEqualTo(steward.id()));
    assertThat(auditCount(groupId, AuditEventType.GROUP_MEMBER_ADDED)).isEqualTo(1);
  }

  /** A member sees the people responsible for their own group by name (Personalrat A4). */
  @Test
  void aMemberSeesTheirGroupsStewardsByName() {
    CurrentUser steward = grantInternalGroupCapability(regularUser());
    UUID groupId =
        groupService.createGroup(new GroupCreation("Team", null), steward).group().getId();
    CurrentUser member = currentUserOf(regularUser());
    groupService.addMember(groupId, member.id(), steward);

    assertThat(groupService.listMyGroups(member))
        .singleElement()
        .satisfies(
            overview ->
                assertThat(overview.stewards())
                    .singleElement()
                    .satisfies(
                        view -> {
                          assertThat(view.steward().getUserId()).isEqualTo(steward.id());
                          assertThat(view.displayName()).isEqualTo("Test User");
                        }));
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private UUID regularUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private UUID systemAdmin() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "admin@example.com", "Test Admin");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private CurrentUser currentUserOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        user.getSystemRole(),
        user.getDisplayName(),
        user.getEmail());
  }

  /** An account without any system role, holding exactly the capability to create groups. */
  private CurrentUser grantInternalGroupCapability(UUID userId) {
    CapabilityGrant grant =
        capabilityGrantRepository.save(
            CapabilityGrant.forUser(
                organizationId, Capability.CREATE_INTERNAL_GROUP, userId, userId));
    createdCapabilityGrantIds.add(grant.getId());
    return currentUserOf(userId);
  }

  /** A library the caller manages, so {@code upsertGrant} gets past its own role check. */
  private UUID libraryManagedBy(CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                organizationId,
                "Bibliothek " + UUID.randomUUID(),
                null,
                caller.id(),
                LibraryVisibility.PRIVATE,
                false));
    assetGrantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            library.getId(),
            organizationId,
            caller.id(),
            AssetRole.OWNER,
            null,
            caller.id()));
    return library.getId();
  }

  private int membershipHistoryRows(UUID groupId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM group_membership_history WHERE group_id = ?",
            Integer.class,
            groupId);
    return count == null ? 0 : count;
  }

  private int auditCount(UUID groupId, AuditEventType type) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type = ?",
            Integer.class,
            groupId.toString(),
            type.name());
    return count == null ? 0 : count;
  }
}
