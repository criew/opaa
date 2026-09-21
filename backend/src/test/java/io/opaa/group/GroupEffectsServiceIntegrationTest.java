package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.Capability;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.CapabilityGrant;
import io.opaa.permission.CapabilityGrantRepository;
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
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
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
    jdbcTemplate.update(
        "DELETE FROM group_membership_history WHERE organization_id = ?", organizationId);
    userRepository.deleteAllById(createdUserIds);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
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

    List<GroupEffectsView> effects = groupEffectsService.listEffects(admin, null);

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

    List<GroupEffectsView> effects = groupEffectsService.listEffects(admin, null);

    assertThat(effectsOf(effects, one).assetGrants()).isEqualTo(1);
    assertThat(effectsOf(effects, one).describe()).isEqualTo("1 Berechtigung an 1 Objekt");
    assertThat(effectsOf(effects, two).assetGrants()).isEqualTo(2);
    assertThat(effectsOf(effects, two).grantedAssets()).isEqualTo(2);
  }

  @Test
  void filteringByAProviderLeavesTheInternalGroupsOut() {
    CurrentUser admin = createAdmin();
    createGroup("Nur intern", admin);

    assertThat(groupEffectsService.listEffects(admin, UUID.randomUUID())).isEmpty();
  }

  private GroupEffectsView effectsOf(List<GroupEffectsView> effects, UUID groupId) {
    return effects.stream()
        .filter(view -> view.groupId().equals(groupId))
        .findFirst()
        .orElseThrow();
  }

  private AssetGrant grantFor(UUID groupId, UUID assetId) {
    return AssetGrant.forGroup(
        KnowledgeLibrary.ASSET_TYPE,
        assetId,
        organizationId,
        groupId,
        AssetRole.VIEWER,
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
}
