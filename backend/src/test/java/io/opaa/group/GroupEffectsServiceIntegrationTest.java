package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.Capability;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.auth.oidc.ProviderGroupDirectory;
import io.opaa.auth.oidc.ProviderGroupEffects;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.CapabilityGrant;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.test.OpaaIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * "Wo wirkt diese Gruppe" (#1821, ADR-0036 Entscheidung 2): the per-group counts the group
 * administration and the work list of one provider read. A group without any effect stays in the
 * answer - that it has none is exactly what makes it deletable with its provider.
 */
@OpaaIntegrationTest
class GroupEffectsServiceIntegrationTest {

  @Autowired private GroupEffectsService groupEffectsService;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private CapabilityGrantRepository capabilityGrantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private ProviderGroupDirectory providerGroupDirectory;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdProviderIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdProviderIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
  }

  @AfterEach
  void tearDown() {
    List<UUID> groupIds =
        groupRepository.findByOrganizationId(organizationId).stream().map(Group::getId).toList();
    if (!groupIds.isEmpty()) {
      capabilityGrantRepository.deleteAll(
          groupIds.stream()
              .flatMap(id -> capabilityGrantRepository.findBySubjectGroupId(id).stream())
              .toList());
      grantRepository.deleteAll(grantRepository.findBySubjectGroupIdIn(groupIds));
      jdbcTemplate.update(
          "DELETE FROM capability_grant_history WHERE organization_id = ?", organizationId);
      jdbcTemplate.update(
          "DELETE FROM asset_grant_history WHERE organization_id = ?", organizationId);
      groupRepository.deleteAllById(groupIds);
    }
    jdbcTemplate.update("DELETE FROM assets WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM group_membership_history WHERE organization_id = ?", organizationId);
    userRepository.deleteAllById(createdUserIds);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    providerRepository.deleteAllById(createdProviderIds);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void everyEffectOfAGroupIsCountedAndPutIntoOneGermanSentence() {
    CurrentUser admin = createAdmin();
    UUID reaching = createGroup("Referat 50", admin);
    UUID idle = createGroup("Arbeitskreis ohne Wirkung", admin);
    UUID libraryOne = UUID.randomUUID();
    UUID libraryTwo = UUID.randomUUID();
    grantRepository.save(grantFor(reaching, libraryOne));
    grantRepository.save(grantFor(reaching, libraryTwo));
    capabilityGrantRepository.save(
        CapabilityGrant.forGroup(organizationId, Capability.CREATE_SPACE, reaching, admin.id()));

    List<GroupEffectsView> effects = groupEffectsService.listEffects(admin, null, List.of());

    GroupEffectsView reachingEffects = effectsOf(effects, reaching);
    assertThat(reachingEffects.assetGrants()).isEqualTo(2);
    assertThat(reachingEffects.grantedAssets()).isEqualTo(2);
    assertThat(reachingEffects.capabilities()).isEqualTo(1);
    assertThat(reachingEffects.any()).isTrue();
    assertThat(reachingEffects.describe())
        .isEqualTo("2 Berechtigungen an 2 Objekten, 1 Anlegerecht");

    GroupEffectsView idleEffects = effectsOf(effects, idle);
    assertThat(idleEffects.any()).isFalse();
    assertThat(idleEffects.describe()).isEmpty();
  }

  /** Jede Gruppe zählt ihre eigenen Zeilen - eine Wirkung wandert nicht zur Nachbargruppe. */
  @Test
  void theGrantsOfOneGroupAreNotCountedAtAnother() {
    CurrentUser admin = createAdmin();
    UUID one = createGroup("Referat 52", admin);
    UUID two = createGroup("Referat 53", admin);
    UUID sharedLibrary = UUID.randomUUID();
    grantRepository.save(grantFor(one, sharedLibrary));
    grantRepository.save(grantFor(two, sharedLibrary));
    grantRepository.save(grantFor(two, UUID.randomUUID()));

    List<GroupEffectsView> effects = groupEffectsService.listEffects(admin, null, List.of());

    assertThat(effectsOf(effects, one).assetGrants()).isEqualTo(1);
    assertThat(effectsOf(effects, one).describe()).isEqualTo("1 Berechtigung an 1 Objekt");
    assertThat(effectsOf(effects, two).assetGrants()).isEqualTo(2);
    assertThat(effectsOf(effects, two).grantedAssets()).isEqualTo(2);
  }

  @Test
  void filteringByAProviderLeavesTheInternalGroupsOut() {
    CurrentUser admin = createAdmin();
    createGroup("Nur intern", admin);

    assertThat(groupEffectsService.listEffects(admin, UUID.randomUUID(), List.of())).isEmpty();
  }

  private GroupEffectsView effectsOf(List<GroupEffectsView> effects, UUID groupId) {
    return effects.stream()
        .filter(view -> view.groupId().equals(groupId))
        .findFirst()
        .orElseThrow();
  }

  /** A grant names an existing asset: its shell row is written here, once per id. */
  private AssetGrant grantFor(UUID groupId, UUID assetId) {
    jdbcTemplate.update(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id)"
            + " VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Bibliothek', 'USER', ?) ON CONFLICT (id) DO NOTHING",
        assetId,
        organizationId,
        createdUserIds.getFirst());
    return AssetGrant.forGroup(
        KnowledgeLibrary.ASSET_TYPE,
        assetId,
        organizationId,
        groupId,
        AssetRole.VIEWER,
        null,
        null,
        null);
  }

  private UUID createGroup(String name, CurrentUser admin) {
    return groupService.createGroup(new GroupCreation(name, null), admin).group().getId();
  }

  private CurrentUser createAdmin() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "admin@example.com", "Test Admin");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    User saved = userRepository.save(user);
    createdUserIds.add(saved.getId());
    return CurrentUser.of(
        saved.getId(), organizationId, SystemRole.SYSTEM_ADMIN, saved.getDisplayName());
  }

  /**
   * The narrowing a list uses for the rows it actually shows, instead of asking for every group.
   */
  @Test
  void onlyTheNamedGroupsAreAnsweredFor() {
    CurrentUser admin = createAdmin();
    UUID wanted = createGroup("Referat 54", admin);
    createGroup("Referat 55", admin);

    List<GroupEffectsView> effects = groupEffectsService.listEffects(admin, null, List.of(wanted));

    assertThat(effects).extracting(GroupEffectsView::groupId).containsExactly(wanted);
  }

  /**
   * The query count stays flat as the list grows - a count per group and effect kind would make an
   * organization with a few hundred directory units a thousand-query page (#1821).
   */
  @Test
  void theNumberOfQueriesDoesNotGrowWithTheNumberOfGroups() {
    CurrentUser admin = createAdmin();
    UUID one = createGroup("Referat 60", admin);
    UUID two = createGroup("Referat 61", admin);
    grantRepository.save(grantFor(one, UUID.randomUUID()));
    grantRepository.save(grantFor(two, UUID.randomUUID()));

    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean previouslyEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      statistics.clear();
      groupEffectsService.listEffects(admin, null, List.of());
      long withTwoGroups = statistics.getPrepareStatementCount();

      UUID three = createGroup("Referat 62", admin);
      grantRepository.save(grantFor(three, UUID.randomUUID()));
      capabilityGrantRepository.save(
          CapabilityGrant.forGroup(organizationId, Capability.CREATE_SPACE, three, admin.id()));

      statistics.clear();
      groupEffectsService.listEffects(admin, null, List.of());
      long withThreeGroups = statistics.getPrepareStatementCount();

      assertThat(withThreeGroups).isEqualTo(withTwoGroups);
    } finally {
      statistics.setStatisticsEnabled(previouslyEnabled);
    }
  }

  /**
   * One definition of "wirkt", held by a test rather than by two hand-kept lists: the work list
   * counts exactly the groups the refused provider deletion counts. An effect kind added to only
   * one of the two sides makes this red.
   */
  @Test
  void theWorkListAndTheRefusedProviderDeletionAgreeOnWhichGroupsAreEffective() {
    CurrentUser admin = createAdmin();
    UUID providerId = createProvider();
    UUID withGrant = createProviderGroup(providerId, "Referat 70");
    UUID withCapability = createProviderGroup(providerId, "Referat 71");
    createProviderGroup(providerId, "Referat 72");
    grantRepository.save(grantFor(withGrant, UUID.randomUUID()));
    capabilityGrantRepository.save(
        CapabilityGrant.forGroup(
            organizationId, Capability.CREATE_SPACE, withCapability, admin.id()));

    List<GroupEffectsView> effects = groupEffectsService.listEffects(admin, providerId, List.of());
    ProviderGroupEffects refusal = providerGroupDirectory.effectsOf(providerId);

    assertThat(effects).hasSize(3);
    assertThat(effects.stream().filter(GroupEffectsView::any).count()).isEqualTo(refusal.groups());
    assertThat(effects.stream().mapToLong(GroupEffectsView::assetGrants).sum())
        .isEqualTo(refusal.grants());
    assertThat(refusal.any()).isTrue();
  }

  private UUID createProvider() {
    OidcProvider provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            null);
    UUID id = providerRepository.save(provider).getId();
    createdProviderIds.add(id);
    return id;
  }

  private UUID createProviderGroup(UUID providerId, String name) {
    Group group =
        new Group(
            organizationId,
            GroupKind.ORG_UNIT,
            name,
            null,
            providerId,
            "ext-" + UUID.randomUUID(),
            "/Haus/" + name,
            null);
    return groupRepository.save(group).getId();
  }
}
