package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.GroupMembershipResolver;
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
 * The Subjekt-Auswahl of ADR-0036, Entscheidung 9 (#1820) against the real schema: which groups a
 * person vergebing a right is offered, with which origin, which size - and which of them is shown
 * as not choosable, with the reason.
 */
@OpaaIntegrationTest
class SelectableGroupSearchIntegrationTest {

  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organization;
  private UUID otherOrganization;
  private UUID provider;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    organization = organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    otherOrganization =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Andere")).getId();
    provider = ProviderFixtures.tokenProvider(providerRepository).getId();
  }

  @AfterEach
  void tearDown() {
    for (String table :
        List.of("group_membership_history", "group_memberships", "group_stewards")) {
      jdbcTemplate.update(
          "DELETE FROM " + table + " WHERE organization_id IN (?, ?)",
          organization,
          otherOrganization);
    }
    jdbcTemplate.update(
        "DELETE FROM groups WHERE organization_id IN (?, ?)", organization, otherOrganization);
    userRepository.deleteAllById(createdUserIds);
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE organization_id IN (?, ?)", organization, otherOrganization);
    organizationRepository.deleteAllById(List.of(organization, otherOrganization));
    providerRepository.deleteById(provider);
  }

  @Test
  void twoSameNamedGroupsAreToldApartByOriginAndSourcePath() {
    createProviderGroup("Referat 50", "/Haus/Abteilung 5/Referat 50");
    createInternalGroup("Referat 50 Projektteam", true);

    List<SelectableGroup> found = groupService.searchSelectableGroups("Referat 5", caller());

    assertThat(found)
        .extracting(selectable -> selectable.group().getName())
        .containsExactlyInAnyOrder("Referat 50", "Referat 50 Projektteam");
    SelectableGroup fromDirectory =
        found.stream().filter(g -> g.provider() != null).findFirst().orElseThrow();
    assertThat(fromDirectory.group().getSourcePath()).isEqualTo("/Haus/Abteilung 5/Referat 50");
    assertThat(fromDirectory.provider().displayName()).isNotBlank();
    assertThat(found.stream().filter(g -> g.provider() == null).findFirst().orElseThrow().group())
        .extracting(Group::getKind)
        .isEqualTo(GroupKind.AD_HOC);
  }

  /** The source path is searchable in its own right - that is what tells subgroups apart. */
  @Test
  void aGroupIsFoundByItsSourcePath() {
    createProviderGroup("Leitung", "/Haus/Abteilung 5/Leitung");

    assertThat(groupService.searchSelectableGroups("Abteilung 5", caller()))
        .extracting(selectable -> selectable.group().getName())
        .containsExactly("Leitung");
  }

  @Test
  void anInternalGroupIsOfferedOnlyOnceItsStewardsReleasedIt() {
    UUID unreleased = createInternalGroup("Vertrauliche Runde", false);

    assertThat(groupService.searchSelectableGroups("Vertrauliche", caller())).isEmpty();

    Group group = groupRepository.findById(unreleased).orElseThrow();
    group.release(true);
    groupRepository.save(group);

    assertThat(groupService.searchSelectableGroups("Vertrauliche", caller()))
        .extracting(selectable -> selectable.group().getId())
        .containsExactly(unreleased);
  }

  @Test
  void anUnreleasedGroupStaysVisibleToItsOwnMembersAndStewardsAndToASystemAdministrator() {
    UUID member = createUser();
    UUID steward = createUser();
    UUID unreleased = createInternalGroup("Vertrauliche Runde", false, member);
    stewardRepository.save(new GroupSteward(unreleased, steward, organization, steward));

    assertThat(groupService.searchSelectableGroups("Vertrauliche", callerOf(member))).hasSize(1);
    assertThat(groupService.searchSelectableGroups("Vertrauliche", callerOf(steward))).hasSize(1);
    assertThat(groupService.searchSelectableGroups("Vertrauliche", systemAdminCaller())).hasSize(1);
  }

  /** A protected group is not findable by a substring; only its complete name reaches it. */
  @Test
  void aProtectedGroupIsReachedOnlyByItsCompleteName() {
    UUID protectedGroup = createInternalGroup("Personalrat", true);
    Group group = groupRepository.findById(protectedGroup).orElseThrow();
    group.markProtected(true);
    groupRepository.save(group);

    assertThat(groupService.searchSelectableGroups("Personal", caller())).isEmpty();

    List<SelectableGroup> found = groupService.searchSelectableGroups("personalrat", caller());

    assertThat(found).hasSize(1);
    assertThat(found.get(0).group().getName()).isEqualTo("Personalrat");
    assertThat(found.get(0).activeMemberCount())
        .as("for a protected group the size is the actual disclosure")
        .isNull();
    assertThat(found.get(0).smallGroup()).isFalse();
  }

  @Test
  void aDissolvedGroupIsShownAsNotSelectableWithItsReason() {
    UUID dissolved = createProviderGroup("Referat 50", null);
    Group group = groupRepository.findById(dissolved).orElseThrow();
    group.dissolve(java.time.Instant.now());
    groupRepository.save(group);

    SelectableGroup found = groupService.searchSelectableGroups("Referat", caller()).get(0);

    assertThat(found.selectable()).isFalse();
    assertThat(found.dissolved()).isTrue();
    assertThat(found.providerDisabled()).isFalse();
  }

  @Test
  void aGroupOfASwitchedOffProviderIsShownAsNotSelectableWithItsReason() {
    createProviderGroup("Referat 50", null);
    OidcProvider disabled = providerRepository.findById(provider).orElseThrow();
    disabled.disable();
    providerRepository.save(disabled);

    SelectableGroup found = groupService.searchSelectableGroups("Referat", caller()).get(0);

    assertThat(found.selectable()).isFalse();
    assertThat(found.providerDisabled()).isTrue();
    assertThat(found.provider().enabled()).isFalse();
  }

  /**
   * ADR-0036, Entscheidung 9: below the Mindestgruppengröße the selection says "kleine Gruppe"
   * instead of a number - the figure itself never leaves the service.
   */
  @Test
  void asmallGroupCarriesNoFigureAndAnEmptyOneIsMarked() {
    createInternalGroup("Kleine Runde", true, createUser());
    createInternalGroup("Leere Runde", true);

    List<SelectableGroup> found = groupService.searchSelectableGroups("Runde", caller());

    SelectableGroup small =
        found.stream()
            .filter(g -> g.group().getName().equals("Kleine Runde"))
            .findFirst()
            .orElseThrow();
    assertThat(small.smallGroup()).isTrue();
    assertThat(small.activeMemberCount()).isNull();
    assertThat(small.emptyGroup()).isFalse();
    assertThat(small.selectable()).as("an effective group stays choosable").isTrue();

    SelectableGroup empty =
        found.stream()
            .filter(g -> g.group().getName().equals("Leere Runde"))
            .findFirst()
            .orElseThrow();
    assertThat(empty.emptyGroup()).isTrue();
    assertThat(empty.selectable())
        .as("an effective but empty group is admitted on purpose, with a warning")
        .isTrue();
  }

  @Test
  void aGroupWithEnoughActiveAccountsCarriesItsNumber() {
    UUID[] members = new UUID[6];
    for (int index = 0; index < members.length; index++) {
      members[index] = createUser();
    }
    createInternalGroup("Referat 50", true, members);

    SelectableGroup found = groupService.searchSelectableGroups("Referat", caller()).get(0);

    assertThat(found.activeMemberCount()).isEqualTo(6);
    assertThat(found.smallGroup()).isFalse();
    assertThat(found.provider()).as("an internal group names no provider").isNull();
  }

  @Test
  void aTooShortQueryAndAnotherOrganizationBothAnswerEmpty() {
    createInternalGroup("Referat 50", true);
    groupRepository.save(Group.internal(otherOrganization, "Referat 50", null, null));

    assertThat(groupService.searchSelectableGroups("R", caller())).isEmpty();
    assertThat(groupService.searchSelectableGroups(null, caller())).isEmpty();
    assertThat(groupService.searchSelectableGroups("Referat", caller())).hasSize(1);
  }

  /**
   * #1820 review: Die Platzhalter der Eingabe bleiben Text. Ohne Escaping traefe eine Anfrage aus
   * zwei Prozentzeichen jede Zeile der Organisation und laedt sie - die Mindestlaenge haelt sie
   * nicht auf.
   */
  @Test
  void likeMetacharactersInTheQueryMatchThemselvesAndNothingElse() {
    createInternalGroup("Referat 50", true);
    createInternalGroup("Prozent %% Runde", true);
    createInternalGroup("Unterstrich _ Runde", true);

    assertThat(groupService.searchSelectableGroups("%%", caller()))
        .extracting(selectable -> selectable.group().getName())
        .containsExactly("Prozent %% Runde");
    assertThat(groupService.searchSelectableGroups("h _ R", caller()))
        .extracting(selectable -> selectable.group().getName())
        .containsExactly("Unterstrich _ Runde");
    assertThat(groupService.searchSelectableGroups("50_", caller()))
        .as("an underscore is a character here, not a single-character wildcard")
        .isEmpty();
  }

  /** Der Deckel liegt in der Datenbank: Die Antwort bleibt bei zwanzig Zeilen. */
  @Test
  void theAnswerIsCappedAtTwentyRows() {
    for (int index = 0; index < 25; index++) {
      createInternalGroup("Referat " + (100 + index), true);
    }

    assertThat(groupService.searchSelectableGroups("Referat 1", caller())).hasSize(20);
  }

  /**
   * #1820 review: Der Exakt-Treffer einer geschuetzten Gruppe haengt nicht davon ab, wie viele
   * andere Gruppen dieselbe Zeichenfolge tragen - er kommt aus einer eigenen Abfrage.
   */
  @Test
  void aProtectedGroupIsFoundByItsCompleteNameEvenBesideManyOtherMatches() {
    UUID protectedGroup = createInternalGroup("Kommission", true);
    Group group = groupRepository.findById(protectedGroup).orElseThrow();
    group.markProtected(true);
    groupRepository.save(group);
    for (int index = 0; index < 25; index++) {
      createInternalGroup("Kommission " + index, true);
    }

    assertThat(groupService.searchSelectableGroups("Kommission", caller()))
        .extracting(selectable -> selectable.group().getId())
        .contains(protectedGroup);
  }

  // -------------------------------------------------------------------------------------------
  // Der Weg ueber die Kennung (#1820)
  // -------------------------------------------------------------------------------------------

  @Test
  void aGroupIsResolvedByItsIdUnderTheSameRuleAsTheSearch() {
    UUID released = createInternalGroup("Referat 50", true);

    SelectableGroup found = groupService.resolveSelectableGroup(released, caller()).orElseThrow();

    assertThat(found.name()).isEqualTo("Referat 50");
    assertThat(found.selectable()).isTrue();
  }

  @Test
  void anUnreleasedGroupDoesNotResolveForAStranger() {
    UUID member = createUser();
    UUID unreleased = createInternalGroup("Vertrauliche Runde", false, member);

    assertThat(groupService.resolveSelectableGroup(unreleased, caller())).isEmpty();
    assertThat(groupService.resolveSelectableGroup(unreleased, callerOf(member))).isPresent();
  }

  @Test
  void anotherOrganizationsGroupAndAnUnknownIdBothResolveToNothing() {
    UUID foreign =
        groupRepository.save(Group.internal(otherOrganization, "Fremd", null, null)).getId();

    assertThat(groupService.resolveSelectableGroup(foreign, caller())).isEmpty();
    assertThat(groupService.resolveSelectableGroup(UUID.randomUUID(), caller())).isEmpty();
  }

  /** ADR-0036, Entscheidung 9: Eine Kennung darf keinen Namen zurueckbringen. */
  @Test
  void aProtectedGroupResolvesWithoutItsName() {
    UUID protectedGroup = createInternalGroup("Personalrat", true);
    Group group = groupRepository.findById(protectedGroup).orElseThrow();
    group.markProtected(true);
    groupRepository.save(group);

    SelectableGroup found =
        groupService.resolveSelectableGroup(protectedGroup, caller()).orElseThrow();

    assertThat(found.name()).isNull();
    assertThat(found.group().isProtectedGroup()).isTrue();
    assertThat(found.activeMemberCount()).isNull();
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private UUID createInternalGroup(String name, boolean released, UUID... memberIds) {
    Group group = Group.internal(organization, name, null, null);
    if (released) {
      group.release(true);
    }
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organization));
    }
    UUID id = groupRepository.save(group).getId();
    membershipResolver.invalidateUsers(List.of(memberIds));
    return id;
  }

  private UUID createProviderGroup(String name, String sourcePath) {
    Group group =
        new Group(
            organization,
            GroupKind.ORG_UNIT,
            name,
            null,
            provider,
            UUID.randomUUID().toString(),
            sourcePath,
            null);
    return groupRepository.save(group).getId();
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organization);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private CurrentUser caller() {
    return callerOf(createUser());
  }

  private CurrentUser callerOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(), user.getOrganizationId(), user.getSystemRole(), user.getDisplayName());
  }

  private CurrentUser systemAdminCaller() {
    UUID id = createUser();
    User user = userRepository.findById(id).orElseThrow();
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    userRepository.save(user);
    return CurrentUser.of(
        user.getId(), user.getOrganizationId(), SystemRole.SYSTEM_ADMIN, user.getDisplayName());
  }
}
