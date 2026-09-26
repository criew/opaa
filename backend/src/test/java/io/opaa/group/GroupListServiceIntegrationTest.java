package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupOrigin;
import io.opaa.api.types.GroupState;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.ProviderFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The administration's paged group list (#1978): every filter narrows by the same state the list
 * shows, the sort orders are the documented ones, and a page is cut from the whole matching set.
 * Six groups cover the five states, both origins and all three kinds.
 */
@OpaaIntegrationTest
class GroupListServiceIntegrationTest {

  @Autowired private GroupListService listService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;

  private UUID organizationId;
  private OidcProvider directoryProvider;
  private OidcProvider disabledProvider;
  private CurrentUser admin;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Gruppenliste")).getId();
    directoryProvider = ProviderFixtures.directoryProvider(providerRepository);
    disabledProvider = ProviderFixtures.tokenProvider(providerRepository);
    disabledProvider.disable();
    providerRepository.save(disabledProvider);
    admin = currentUserOf(createUser(SystemRole.SYSTEM_ADMIN));

    Group alpha = internal("Alpha intern", "Projektgruppe Phoenix");
    alpha.release(true);
    alpha.addMembership(new GroupMembership(createUser(SystemRole.USER), organizationId));
    alpha.addMembership(new GroupMembership(createUser(SystemRole.USER), organizationId));
    groupRepository.save(alpha);
    groupRepository.save(internal("Beta intern", null));
    Group dissolved = internal("Gamma aufgelöst", null);
    dissolved.release(true);
    dissolved.dissolve(Instant.now());
    groupRepository.save(dissolved);
    groupRepository.save(
        new Group(
            organizationId,
            GroupKind.ORG_UNIT,
            "Referat 50",
            null,
            directoryProvider.getId(),
            "ou-50",
            "/Kreis/Soziales",
            null));
    groupRepository.save(
        new Group(
            organizationId,
            GroupKind.IDENTITY_PROVIDER,
            "Token-Gruppe",
            null,
            directoryProvider.getId(),
            null,
            null,
            null));
    groupRepository.save(
        new Group(
            organizationId,
            GroupKind.IDENTITY_PROVIDER,
            "Kfz",
            null,
            disabledProvider.getId(),
            null,
            null,
            null));
  }

  @AfterEach
  void tearDown() {
    groupRepository.deleteAll(
        groupRepository.findAll().stream()
            .filter(group -> group.getOrganizationId().equals(organizationId))
            .toList());
    userRepository.deleteAllById(createdUserIds);
    organizationRepository.deleteById(organizationId);
    providerRepository.deleteAllById(List.of(directoryProvider.getId(), disabledProvider.getId()));
  }

  @Test
  void aPageIsCutFromTheWholeSetInNameOrder() {
    GroupPage page = listService.pageGroups(admin, query(null, null, null, null, 1, 2));

    assertThat(page.total()).isEqualTo(6);
    assertThat(names(page)).containsExactly("Gamma aufgelöst", "Kfz");
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(2);
  }

  @Test
  void everyGroupCarriesTheStateTheFilterSelectsBy() {
    assertThat(namesIn(GroupState.DISSOLVED)).containsExactly("Gamma aufgelöst");
    assertThat(namesIn(GroupState.PROVIDER_DISABLED)).containsExactly("Kfz");
    assertThat(namesIn(GroupState.UNMAINTAINED)).containsExactly("Token-Gruppe");
    assertThat(namesIn(GroupState.NOT_RELEASED)).containsExactly("Beta intern");
    assertThat(namesIn(GroupState.ACTIVE)).containsExactly("Alpha intern", "Referat 50");
  }

  @Test
  void filtersByOriginProviderKindAndSearchText() {
    assertThat(names(listService.pageGroups(admin, filter(GroupOrigin.INTERNAL, null, null, null))))
        .containsExactly("Alpha intern", "Beta intern", "Gamma aufgelöst");
    assertThat(
            names(
                listService.pageGroups(admin, filter(null, directoryProvider.getId(), null, null))))
        .containsExactly("Referat 50", "Token-Gruppe");
    assertThat(names(listService.pageGroups(admin, filter(null, null, GroupKind.ORG_UNIT, null))))
        .containsExactly("Referat 50");
    // the search covers name, description and source path, case-insensitively
    assertThat(names(listService.pageGroups(admin, filter(null, null, null, "PHOENIX"))))
        .containsExactly("Alpha intern");
    assertThat(names(listService.pageGroups(admin, filter(null, null, null, "soziales"))))
        .containsExactly("Referat 50");
  }

  @Test
  void sortsByStateMemberCountAndOrigin() {
    assertThat(names(listService.pageGroups(admin, sorted(GroupListQuery.Sort.STATE, false))))
        .containsExactly(
            "Gamma aufgelöst", "Kfz", "Token-Gruppe", "Beta intern", "Alpha intern", "Referat 50");
    assertThat(names(listService.pageGroups(admin, sorted(GroupListQuery.Sort.MEMBER_COUNT, true))))
        .first()
        .isEqualTo("Alpha intern");
    assertThat(names(listService.pageGroups(admin, sorted(GroupListQuery.Sort.ORIGIN, false))))
        .startsWith("Alpha intern", "Beta intern", "Gamma aufgelöst");
  }

  @Test
  void refusesAProviderTogetherWithTheInternalOrigin() {
    assertThatThrownBy(() -> filter(GroupOrigin.INTERNAL, directoryProvider.getId(), null, null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> query(null, null, null, null, 0, 51))
        .isInstanceOf(ValidationException.class);
  }

  private List<String> namesIn(GroupState state) {
    return names(listService.pageGroups(admin, query(null, null, null, state, 0, 50)));
  }

  private static List<String> names(GroupPage page) {
    return page.items().stream().map(overview -> overview.group().getName()).toList();
  }

  private static GroupListQuery query(
      GroupOrigin origin, UUID providerId, GroupKind kind, GroupState state, int page, int size) {
    return new GroupListQuery(
        null, origin, providerId, kind, state, GroupListQuery.Sort.NAME, false, page, size);
  }

  private static GroupListQuery filter(
      GroupOrigin origin, UUID providerId, GroupKind kind, String text) {
    return new GroupListQuery(
        text, origin, providerId, kind, null, GroupListQuery.Sort.NAME, false, 0, 50);
  }

  private static GroupListQuery sorted(GroupListQuery.Sort sort, boolean descending) {
    return new GroupListQuery(null, null, null, null, null, sort, descending, 0, 50);
  }

  private Group internal(String name, String description) {
    return new Group(organizationId, GroupKind.AD_HOC, name, description, null, null, null, null);
  }

  private UUID createUser(SystemRole role) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "gruppen@example.com", "Gruppen");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
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
}
