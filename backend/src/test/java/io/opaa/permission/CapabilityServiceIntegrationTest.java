package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
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
 * <p>The delivered grants are installation-wide rows without an id a class could scope a cleanup
 * to; {@code SeededRowRestorer} puts them - and every interval this class opens - back after each
 * method, which is why withdrawing from "Alle Konten" here does not leave the next class unable to
 * create a space.
 */
@OpaaIntegrationTest
class CapabilityServiceIntegrationTest {

  @Autowired private CapabilityService capabilityService;
  @Autowired private CapabilityGrantRepository grantRepository;
  @Autowired private CapabilityGrantHistoryRepository historyRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryAccessService libraryAccessService;

  private final List<UUID> userIds = new ArrayList<>();
  private final List<UUID> groupIds = new ArrayList<>();
  private final List<UUID> libraryIds = new ArrayList<>();

  private CurrentUser member;
  private CurrentUser admin;

  @BeforeEach
  void setUp() {
    cleanUp();
    member = persistUser(SystemRole.USER);
    admin = persistUser(SystemRole.SYSTEM_ADMIN);
  }

  @AfterEach
  void cleanUp() {
    grantRepository.deleteAll(
        grantRepository.findByOrganizationId(Organization.DEFAULT_ID).stream()
            .filter(grant -> grant.getSubjectType() != CapabilitySubjectType.ALL_ACCOUNTS)
            .filter(
                grant ->
                    userIds.contains(grant.getSubjectUserId())
                        || groupIds.contains(grant.getSubjectGroupId()))
            .toList());
    historyRepository.deleteBySubjectUserIdIn(userIds);
    libraryRepository.deleteAll(libraryRepository.findAllById(libraryIds));
    groupRepository.deleteAll(groupRepository.findAllById(groupIds));
    userRepository.deleteAll(userRepository.findAllById(userIds));
    userIds.forEach(membershipResolver::invalidateUser);
    libraryIds.clear();
    groupIds.clear();
    userIds.clear();
  }

  @Test
  void deliversTheThreeCreationCapabilitiesToEveryAccountAndTheGroupToNobody() {
    assertThat(capabilityService.capabilitiesOf(member))
        .as("after the migration every account creates spaces and libraries exactly as before")
        .containsExactlyInAnyOrder(
            Capability.CREATE_SPACE,
            Capability.CREATE_LIBRARY,
            Capability.CREATE_CONNECTOR_LIBRARY);
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
            LibraryVisibility.PRIVATE,
            false);
    UUID libraryId = libraryRepository.save(library).getId();
    libraryIds.add(libraryId);
    return libraryId;
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
