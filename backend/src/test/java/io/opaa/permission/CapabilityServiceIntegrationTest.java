package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The capability evaluation against the real schema (#1813, ADR-0036 Entscheidung 5): the delivered
 * state, the four ways a capability is reached, and that a withdrawal takes effect on the next
 * request rather than on the next sign-in.
 *
 * <p>{@code SeededRowRestorer} puts the delivered grants and their intervals back after each
 * method, which is why withdrawing from "Alle Konten" here does not leave the next class unable to
 * create a space. It restores only the rows it found, so every row this class writes - grants, the
 * intervals behind them and the revocation markers - is cleaned up here.
 */
@OpaaIntegrationTest
class CapabilityServiceIntegrationTest {

  @Autowired private CapabilityService capabilityService;
  @Autowired private CapabilityGrantRepository grantRepository;
  @Autowired private CapabilityGrantHistoryRepository historyRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupService groupService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryAccessService libraryAccessService;

  private final List<UUID> userIds = new ArrayList<>();
  private final List<UUID> groupIds = new ArrayList<>();
  private final List<UUID> libraryIds = new ArrayList<>();

  /**
   * The interval rows present before this method ran; what appeared since is this method's own and
   * is removed below. The revocation markers and the group intervals never reach the test code by
   * id, and a filter over their shape would take a neighbour's rows with it. {@code null} until the
   * first snapshot exists - the cleanup that opens the first method has nothing to compare against
   * and must not mistake the whole table for its own.
   */
  private List<UUID> foreignHistoryIds;

  private CurrentUser member;
  private CurrentUser admin;

  @BeforeEach
  void setUp() {
    cleanUp();
    foreignHistoryIds = historyIds();
    member = persistUser(SystemRole.USER);
    admin = persistUser(SystemRole.SYSTEM_ADMIN);
  }

  @AfterEach
  void cleanUp() {
    if (foreignHistoryIds != null) {
      List<UUID> ownHistory = new ArrayList<>(historyIds());
      ownHistory.removeAll(foreignHistoryIds);
      historyRepository.deleteAllById(ownHistory);
    }
    grantRepository.deleteAll(
        grantRepository.findByOrganizationId(Organization.DEFAULT_ID).stream()
            .filter(grant -> grant.getSubjectType() != CapabilitySubjectType.ALL_ACCOUNTS)
            .filter(
                grant ->
                    userIds.contains(grant.getSubjectUserId())
                        || groupIds.contains(grant.getSubjectGroupId()))
            .toList());
    libraryRepository.deleteAll(libraryRepository.findAllById(libraryIds));
    groupRepository.deleteAll(groupRepository.findAllById(groupIds));
    userRepository.deleteAll(userRepository.findAllById(userIds));
    userIds.forEach(membershipResolver::invalidateUser);
    libraryIds.clear();
    groupIds.clear();
    userIds.clear();
  }

  private List<UUID> historyIds() {
    return jdbcTemplate.queryForList("SELECT id FROM capability_grant_history", UUID.class);
  }

  @Test
  void deliversTheFourCreationCapabilitiesToEveryAccountAndTheGroupToNobody() {
    assertThat(capabilityService.capabilitiesOf(member))
        .as(
            "after the migration every account creates spaces, libraries and prompt libraries;"
                + " internal groups stay with the system administration")
        .containsExactlyInAnyOrder(
            Capability.CREATE_SPACE,
            Capability.CREATE_LIBRARY,
            Capability.CREATE_CONNECTOR_LIBRARY,
            Capability.CREATE_PROMPT_LIBRARY);
  }

  @Test
  void givesASystemAdministratorEveryCapabilityImplicitly() {
    assertThat(capabilityService.capabilitiesOf(admin))
        .containsExactlyInAnyOrder(Capability.values());
  }

  /** The role is a reading path into the protocol and nothing else (ADR-0036, Entscheidung 5). */
  @Test
  void givesTheAuditorRoleNoCapabilityOfItsOwn() {
    CurrentUser auditor = persistUser(SystemRole.AUDITOR);

    assertThat(capabilityService.capabilitiesOf(auditor))
        .isEqualTo(capabilityService.capabilitiesOf(member));
  }

  @Test
  void refusesTheCreationPathWithItsOwnCodeOnceAllAccountsLoseTheCapability() {
    revokeFromAllAccounts(Capability.CREATE_SPACE);

    assertThatThrownBy(() -> capabilityService.requireCapability(member, Capability.CREATE_SPACE))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Anlegerecht")
        .hasMessageContaining("Systemverwaltung")
        .extracting(denied -> ((AccessDeniedException) denied).getCode())
        .isEqualTo(CapabilityService.CAPABILITY_REQUIRED);
    assertThat(capabilityService.hasCapability(admin, Capability.CREATE_SPACE))
        .as("the system administration keeps every capability implicitly")
        .isTrue();
  }

  @Test
  void reachesACapabilityThroughAGroupTheAccountIsAMemberOf() {
    revokeFromAllAccounts(Capability.CREATE_CONNECTOR_LIBRARY);
    UUID groupId = persistGroupWithMember(member.id());

    capabilityService.grant(
        Capability.CREATE_CONNECTOR_LIBRARY, CapabilitySubjectType.GROUP, groupId, admin);

    assertThat(capabilityService.hasCapability(member, Capability.CREATE_CONNECTOR_LIBRARY))
        .isTrue();
    CurrentUser outsider = persistUser(SystemRole.USER);
    assertThat(capabilityService.hasCapability(outsider, Capability.CREATE_CONNECTOR_LIBRARY))
        .as("only the members of the group, plus the system administration")
        .isFalse();
  }

  /**
   * The promise of ADR-0036, Entscheidung 5 that ADR-0021 carries: the capability is read per call,
   * so a withdrawal reaches the very next request of a session that is already running.
   */
  @Test
  void aWithdrawalTakesEffectWithoutANewSignIn() {
    UUID groupId = persistGroupWithMember(member.id());
    revokeFromAllAccounts(Capability.CREATE_SPACE);
    CapabilityGrant grant =
        capabilityService.grant(
            Capability.CREATE_SPACE, CapabilitySubjectType.GROUP, groupId, admin);
    assertThat(capabilityService.hasCapability(member, Capability.CREATE_SPACE)).isTrue();

    capabilityService.revoke(Capability.CREATE_SPACE, grant.getId(), admin);

    assertThat(capabilityService.hasCapability(member, Capability.CREATE_SPACE))
        .as("same CurrentUser snapshot, same session - only the next read differs")
        .isFalse();
  }

  @Test
  void recordsGrantAndWithdrawalAsAnIntervalAndAsAGovernanceEvent() {
    CapabilityGrant grant =
        capabilityService.grant(
            Capability.CREATE_INTERNAL_GROUP, CapabilitySubjectType.USER, member.id(), admin);

    assertThat(
            historyRepository
                .findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                    Organization.DEFAULT_ID,
                    Capability.CREATE_INTERNAL_GROUP,
                    CapabilitySubjectType.USER,
                    member.id()))
        .as("an open interval is the right in force")
        .isPresent();
    assertThat(auditTypesFor(Capability.CREATE_INTERNAL_GROUP))
        .contains(AuditEventType.CAPABILITY_GRANTED.name());

    capabilityService.revoke(Capability.CREATE_INTERNAL_GROUP, grant.getId(), admin);

    assertThat(
            historyRepository
                .findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                    Organization.DEFAULT_ID,
                    Capability.CREATE_INTERNAL_GROUP,
                    CapabilitySubjectType.USER,
                    member.id()))
        .as("the withdrawal closes it")
        .isEmpty();
    assertThat(auditTypesFor(Capability.CREATE_INTERNAL_GROUP))
        .contains(AuditEventType.CAPABILITY_REVOKED.name());
  }

  @Test
  void refusesASecondGrantOfTheSameCapabilityToTheSameSubject() {
    capabilityService.grant(
        Capability.CREATE_INTERNAL_GROUP, CapabilitySubjectType.USER, member.id(), admin);

    assertThatThrownBy(
            () ->
                capabilityService.grant(
                    Capability.CREATE_INTERNAL_GROUP,
                    CapabilitySubjectType.USER,
                    member.id(),
                    admin))
        .isInstanceOf(ConflictException.class);
  }

  /**
   * ADR-0036, Entscheidung 9 (#1820): Die Liste der Anlegerechte ist eine fremde Liste - eine
   * geschuetzte Gruppe steht dort ohne ihren Namen.
   */
  @Test
  void namesNoProtectedGroupInTheOverview() {
    Group group = Group.internal(Organization.DEFAULT_ID, "Personalrat", null, null);
    group.markProtected(true);
    UUID groupId = groupRepository.save(group).getId();
    CapabilityGrant granted =
        capabilityService.grant(
            Capability.CREATE_INTERNAL_GROUP, CapabilitySubjectType.GROUP, groupId, admin);

    List<CapabilityGrantView> grants =
        capabilityService.overview(Organization.DEFAULT_ID).stream()
            .filter(entry -> entry.capability() == Capability.CREATE_INTERNAL_GROUP)
            .flatMap(entry -> entry.grants().stream())
            .toList();

    assertThat(grants)
        .extracting(CapabilityGrantView::subjectName)
        .containsExactly("Geschützte Gruppe");
    capabilityService.revoke(Capability.CREATE_INTERNAL_GROUP, granted.getId(), admin);
    groupRepository.deleteById(groupId);
  }

  @Test
  void listsEveryCapabilityIncludingTheOnesNobodyHolds() {
    assertThat(capabilityService.overview(Organization.DEFAULT_ID))
        .extracting(CapabilityOverview::capability)
        .containsExactly(Capability.values());
    assertThat(
            capabilityService.overview(Organization.DEFAULT_ID).stream()
                .filter(entry -> entry.capability() == Capability.CREATE_INTERNAL_GROUP)
                .flatMap(entry -> entry.grants().stream())
                .toList())
        .as("delivered to nobody")
        .isEmpty();
  }

  /**
   * The promise of ADR-0036, Entscheidung 1 in testable form: a capability opens a creation path
   * and never an existing content. Held against the search formula itself - the one place a bypass
   * would leak rather than merely inconvenience - with every capability granted to the account at
   * once.
   */
  @Test
  void noCapabilityWidensTheSetOfReadableLibraries() {
    UUID foreign = persistPrivateLibraryOwnedBy(admin.id());
    for (Capability capability : Capability.values()) {
      if (!capabilityService.hasCapability(member, capability)) {
        capabilityService.grant(capability, CapabilitySubjectType.USER, member.id(), admin);
      }
    }

    assertThat(capabilityService.capabilitiesOf(member))
        .containsExactlyInAnyOrder(Capability.values());
    assertThat(libraryAccessService.readableLibraryIds(member.id(), Organization.DEFAULT_ID))
        .as("holding every Anlegerecht reaches no content whatsoever")
        .doesNotContain(foreign);
  }

  private UUID persistPrivateLibraryOwnedBy(UUID ownerId) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "Fremde Bibliothek " + UUID.randomUUID(),
            null,
            ownerId,
            false);
    UUID libraryId = libraryRepository.save(library).getId();
    libraryIds.add(libraryId);
    return libraryId;
  }

  /**
   * {@code fk_capability_grants_subject_group_organization} is RESTRICT: without the check in
   * {@code GroupService#deleteGroup} the deletion is refused by the constraint alone, with the
   * generic foreign-key message and without naming the Anlegerecht as the reason.
   */
  @Test
  void aGroupHoldingAnAnlegerechtIsNotDeletedAndTheRefusalSaysWhy() {
    UUID groupId = persistGroupWithMember(member.id());
    capabilityService.grant(Capability.CREATE_SPACE, CapabilitySubjectType.GROUP, groupId, admin);

    assertThatThrownBy(() -> groupService.deleteGroup(groupId, admin))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Anlegerechte");
    assertThat(groupRepository.findById(groupId)).isPresent();
  }

  @Test
  void refusesAGrantToAllAccountsThatNamesASubjectAnyway() {
    assertThatThrownBy(
            () ->
                capabilityService.grant(
                    Capability.CREATE_SPACE,
                    CapabilitySubjectType.ALL_ACCOUNTS,
                    member.id(),
                    admin))
        .as("a silently dropped subjectId would answer 201 to a request nobody meant that way")
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ALL_ACCOUNTS");
  }

  /**
   * The Stichtag side of the same rows, which the reading path of #1822 will call: an interval
   * covers {@code asOf} when it began at or before it and has not ended by then. The zero-length
   * revocation marker satisfies neither half of {@code validTo > asOf} and is therefore never
   * selected - it is an event, not a state.
   */
  @Test
  void reconstructsWhoHeldACapabilityAtAPastInstantWithoutTheRevocationMarkers() {
    CapabilityGrant grant =
        capabilityService.grant(
            Capability.CREATE_INTERNAL_GROUP, CapabilitySubjectType.USER, member.id(), admin);
    // The interval's own boundary, not a wall-clock reading: PermissionHistoryClock may run a
    // microsecond ahead of the wall clock inside one tick, which would make a reading taken here
    // land on the wrong side of the boundary.
    Instant whileHeld =
        historyRepository
            .findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                Organization.DEFAULT_ID,
                Capability.CREATE_INTERNAL_GROUP,
                CapabilitySubjectType.USER,
                member.id())
            .orElseThrow()
            .getValidFrom();
    Instant beforeTheGrant = whileHeld.minusSeconds(60);
    capabilityService.revoke(Capability.CREATE_INTERNAL_GROUP, grant.getId(), admin);
    Instant afterTheWithdrawal = Instant.now().plusSeconds(60);

    assertThat(holdersAsOf(beforeTheGrant)).doesNotContain(member.id());
    assertThat(holdersAsOf(whileHeld)).contains(member.id());
    assertThat(holdersAsOf(afterTheWithdrawal))
        .as("the closed interval has ended and the marker is zero-length")
        .doesNotContain(member.id());
  }

  private List<UUID> holdersAsOf(Instant asOf) {
    return historyRepository
        .findHoldersAsOf(Organization.DEFAULT_ID, Capability.CREATE_INTERNAL_GROUP, asOf)
        .stream()
        .map(CapabilityGrantHistory::getSubjectUserId)
        .toList();
  }

  private void revokeFromAllAccounts(Capability capability) {
    CapabilityGrant delivered =
        grantRepository
            .findByOrganizationIdAndCapabilityAndSubjectType(
                Organization.DEFAULT_ID, capability, CapabilitySubjectType.ALL_ACCOUNTS)
            .orElseThrow();
    capabilityService.revoke(capability, delivered.getId(), admin);
  }

  /** Scoped to the one audit object a capability has - never a read over the whole table. */
  private List<String> auditTypesFor(Capability capability) {
    return jdbcTemplate.queryForList(
        "SELECT event_type FROM audit_log WHERE object_id = ?",
        String.class,
        CapabilityService.capabilityObjectId(capability).toString());
  }

  private CurrentUser persistUser(SystemRole role) {
    User user =
        new User(
            "capability-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            "Fähigkeitenprüfung");
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(role);
    User saved = userRepository.save(user);
    userIds.add(saved.getId());
    return CurrentUser.of(saved.getId(), Organization.DEFAULT_ID, role, saved.getDisplayName());
  }

  private UUID persistGroupWithMember(UUID userId) {
    Group group =
        Group.internal(Organization.DEFAULT_ID, "Anlegende " + UUID.randomUUID(), null, null);
    group.addMembership(new GroupMembership(userId, Organization.DEFAULT_ID));
    UUID groupId = groupRepository.save(group).getId();
    groupIds.add(groupId);
    membershipResolver.invalidateUser(userId);
    return groupId;
  }
}
