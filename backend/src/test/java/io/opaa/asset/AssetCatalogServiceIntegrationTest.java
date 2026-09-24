package io.opaa.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
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
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The catalog of #1904 over both asset types: readable (direct grant, group grant,
 * organization-wide) united with listed, from one query on the shell - with {@code accessible} by
 * the rights formula alone, the organization boundary, the search and the paging in SQL.
 */
@OpaaIntegrationTest
class AssetCatalogServiceIntegrationTest {

  private static final List<AssetType> TYPES =
      List.of(KnowledgeLibrary.ASSET_TYPE, PromptLibrary.ASSET_TYPE);

  @Autowired private AssetCatalogService catalogService;
  @Autowired private KnowledgeLibraryRepository knowledgeLibraryRepository;
  @Autowired private PromptLibraryRepository promptLibraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organization;
  private UUID foreignOrganization;
  private UUID owner;
  private UUID member;
  private UUID outsider;
  private UUID administrator;
  private UUID foreigner;
  private UUID group;

  @BeforeEach
  void setUp() {
    organization = createOrganization("Katalog");
    foreignOrganization = createOrganization("Fremder Katalog");
    owner = createUser(organization, "Eigentümerin");
    member = createUser(organization, "Gruppenmitglied");
    outsider = createUser(organization, "Außenstehende");
    administrator = createUser(organization, "Systemverwaltung");
    foreigner = createUser(foreignOrganization, "Fremde");
    group = createGroup(organization, "Referat 50", member);
  }

  @AfterEach
  void tearDown() {
    for (UUID id : List.of(organization, foreignOrganization)) {
      jdbcTemplate.update("DELETE FROM assets WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_ownership_history WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM succession_cases WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM group_membership_history WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM group_memberships WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM groups WHERE organization_id = ?", id);
    }
    ownOrganizationFixtures.removeOrganizations(organization, foreignOrganization);
  }

  @Test
  void everyoneSeesWhatTheyMayReadUnitedWithTheListedAssetsOfBothTypes() {
    Map<String, UUID> assets = createTheFourStagesOfBothTypes();

    assertThat(accessibleByName(callerOf(owner)))
        .as("the owner reads all eight")
        .containsOnlyKeys(assets.keySet())
        .allSatisfy((name, accessible) -> assertThat(accessible).isTrue());
    assertThat(accessibleByName(callerOf(member)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "Gruppe Wissen", true,
                "Gruppe Prompts", true,
                "Organisation Wissen", true,
                "Organisation Prompts", true,
                "Gelistet Wissen", false,
                "Gelistet Prompts", false));
    assertThat(accessibleByName(callerOf(outsider)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "Organisation Wissen", true,
                "Organisation Prompts", true,
                "Gelistet Wissen", false,
                "Gelistet Prompts", false));
  }

  @Test
  void aSystemAdministratorWithoutAGrantFindsTheListedAssetsButCannotReadThem() {
    createTheFourStagesOfBothTypes();

    assertThat(accessibleByName(callerOf(administrator, true)))
        .as("administering is never reading: accessible follows the formula alone")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "Organisation Wissen", true,
                "Organisation Prompts", true,
                "Gelistet Wissen", false,
                "Gelistet Prompts", false));
  }

  @Test
  void theCatalogNeverCrossesTheOrganizationBoundary() {
    createTheFourStagesOfBothTypes();
    UUID foreignOwner = createUser(foreignOrganization, "Fremde Eigentümerin");
    promptLibrary(
        foreignOrganization, "Fremd gelistet", foreignOwner, AssetVisibility.PRIVATE, true);
    knowledgeLibrary(
        foreignOrganization,
        "Fremd organisationsweit",
        foreignOwner,
        AssetVisibility.ORGANIZATION,
        false);

    assertThat(accessibleByName(callerOf(owner)))
        .doesNotContainKeys("Fremd gelistet", "Fremd organisationsweit");
    assertThat(accessibleByName(callerOf(administrator, true)))
        .doesNotContainKeys("Fremd gelistet", "Fremd organisationsweit");
    assertThat(accessibleByName(callerOf(foreigner)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("Fremd gelistet", false, "Fremd organisationsweit", true));
  }

  @Test
  void anEntryNamesTypeOwnerOriginAndListing() {
    UUID groupOwned =
        promptLibraryRepository
            .save(
                PromptLibrary.ownedByGroup(
                    organization,
                    "Referatsvorlagen",
                    "Hausstandard",
                    group,
                    AssetVisibility.PRIVATE,
                    true))
            .getId();
    UUID personOwned =
        knowledgeLibrary(organization, "Rechtsquellen", owner, AssetVisibility.ORGANIZATION, false);

    List<AssetCatalogEntry> entries =
        catalogService.list(callerOf(outsider), null, null, 0, 50).entries();

    AssetCatalogEntry prompts = entryFor(entries, groupOwned);
    assertThat(prompts.asset().getAssetType()).isEqualTo(PromptLibrary.ASSET_TYPE);
    assertThat(prompts.asset().getDescription()).isEqualTo("Hausstandard");
    assertThat(prompts.asset().getOrigin()).isEqualTo(AssetOrigin.LOCAL);
    assertThat(prompts.asset().isListed()).isTrue();
    assertThat(prompts.ownerLabel()).as("the owning group, not a person").isEqualTo("Referat 50");
    assertThat(prompts.accessible()).isFalse();
    AssetCatalogEntry knowledge = entryFor(entries, personOwned);
    assertThat(knowledge.asset().getAssetType()).isEqualTo(KnowledgeLibrary.ASSET_TYPE);
    assertThat(knowledge.ownerLabel()).isEqualTo("Eigentümerin");
    assertThat(knowledge.asset().isListed()).isFalse();
    assertThat(knowledge.accessible()).isTrue();
    assertThat(knowledge.succession()).isNull();
  }

  @Test
  void anAssetWhoseOwnerLeftCarriesItsOpenSuccessionInTheCatalog() {
    UUID id = promptLibrary(organization, "Verwaist", owner, AssetVisibility.PRIVATE, true);
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", owner);

    AssetCatalogEntry entry =
        entryFor(catalogService.list(callerOf(outsider), null, null, 0, 50).entries(), id);

    assertThat(entry.succession()).isNotNull();
    assertThat(entry.succession().addressee()).isEqualTo(SuccessionAddressee.SYSTEM_ADMINISTRATION);
  }

  @Test
  void theTypeFilterAndTheSearchNarrowTheCatalogInTheQuery() {
    createTheFourStagesOfBothTypes();
    promptLibrary(organization, "Rabatt 100%", owner, AssetVisibility.ORGANIZATION, false);
    promptLibrary(organization, "Rabatt 1000", owner, AssetVisibility.ORGANIZATION, false);
    promptLibraryRepository.save(
        PromptLibrary.ownedByUser(
            organization,
            "Vermerke",
            "Formulierungen für die ANHÖRUNG",
            owner,
            AssetVisibility.ORGANIZATION,
            false));

    assertThat(
            names(catalogService.list(callerOf(outsider), PromptLibrary.ASSET_TYPE, null, 0, 50)))
        .containsExactly(
            "Gelistet Prompts", "Organisation Prompts", "Rabatt 100%", "Rabatt 1000", "Vermerke");
    assertThat(
            names(
                catalogService.list(callerOf(outsider), KnowledgeLibrary.ASSET_TYPE, null, 0, 50)))
        .containsExactly("Gelistet Wissen", "Organisation Wissen");
    assertThat(names(catalogService.list(callerOf(outsider), null, "gelistet", 0, 50)))
        .as("case-insensitive on the name")
        .containsExactly("Gelistet Prompts", "Gelistet Wissen");
    assertThat(names(catalogService.list(callerOf(outsider), null, "anhörung", 0, 50)))
        .as("the description counts, and the search is case-insensitive beyond ASCII")
        .containsExactly("Vermerke");
    assertThat(names(catalogService.list(callerOf(outsider), null, "100%", 0, 50)))
        .as("a wildcard in the search text is taken literally")
        .containsExactly("Rabatt 100%");
    assertThat(names(catalogService.list(callerOf(outsider), null, "Privat", 0, 50)))
        .as("the search never widens the set")
        .isEmpty();
  }

  @Test
  void theCatalogIsPagedByNameWithTheTotalOfTheWholeSet() {
    createTheFourStagesOfBothTypes();

    AssetCatalogPage first = catalogService.list(callerOf(outsider), null, null, 0, 3);
    AssetCatalogPage second = catalogService.list(callerOf(outsider), null, null, 1, 3);

    assertThat(names(first))
        .containsExactly("Gelistet Prompts", "Gelistet Wissen", "Organisation Prompts");
    assertThat(names(second)).containsExactly("Organisation Wissen");
    assertThat(first.totalElements()).isEqualTo(4);
    assertThat(first.totalPages()).isEqualTo(2);
    assertThat(names(catalogService.list(callerOf(outsider), null, null, 5, 3))).isEmpty();
  }

  @Test
  void aPageSizeOrSearchTextOutsideTheBoundsIsRefusedRatherThanCorrected() {
    assertThatThrownBy(() -> catalogService.list(callerOf(owner), null, null, -1, 10))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> catalogService.list(callerOf(owner), null, null, 0, 0))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> catalogService.list(callerOf(owner), null, null, 0, 201))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> catalogService.list(callerOf(owner), null, "x".repeat(201), 0, 10))
        .isInstanceOf(ValidationException.class);
  }

  /**
   * Per type: private (owner only), shared with the group, released organization-wide, and private
   * but listed.
   */
  private Map<String, UUID> createTheFourStagesOfBothTypes() {
    Map<String, UUID> assets = new LinkedHashMap<>();
    for (AssetType type : TYPES) {
      String suffix = type.equals(KnowledgeLibrary.ASSET_TYPE) ? " Wissen" : " Prompts";
      assets.put("Privat" + suffix, asset(type, "Privat" + suffix, AssetVisibility.PRIVATE, false));
      UUID shared = asset(type, "Gruppe" + suffix, AssetVisibility.SHARED, false);
      grantRepository.save(
          AssetGrant.forGroup(type, shared, organization, group, AssetRole.VIEWER, null, owner, 1));
      assets.put("Gruppe" + suffix, shared);
      assets.put(
          "Organisation" + suffix,
          asset(type, "Organisation" + suffix, AssetVisibility.ORGANIZATION, false));
      assets.put(
          "Gelistet" + suffix, asset(type, "Gelistet" + suffix, AssetVisibility.PRIVATE, true));
    }
    return assets;
  }

  private UUID asset(AssetType type, String name, AssetVisibility visibility, boolean listed) {
    return type.equals(KnowledgeLibrary.ASSET_TYPE)
        ? knowledgeLibrary(organization, name, owner, visibility, listed)
        : promptLibrary(organization, name, owner, visibility, listed);
  }

  private UUID knowledgeLibrary(
      UUID organizationId, String name, UUID ownerId, AssetVisibility visibility, boolean listed) {
    UUID id =
        knowledgeLibraryRepository
            .save(
                KnowledgeLibrary.ownedByUser(
                    organizationId, name, null, ownerId, visibility, listed))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            id,
            organizationId,
            ownerId,
            AssetRole.OWNER,
            null,
            ownerId));
    return id;
  }

  private UUID promptLibrary(
      UUID organizationId, String name, UUID ownerId, AssetVisibility visibility, boolean listed) {
    UUID id =
        promptLibraryRepository
            .save(
                PromptLibrary.ownedByUser(organizationId, name, null, ownerId, visibility, listed))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            PromptLibrary.ASSET_TYPE, id, organizationId, ownerId, AssetRole.OWNER, null, ownerId));
    return id;
  }

  private Map<String, Boolean> accessibleByName(CurrentUser caller) {
    return catalogService.list(caller, null, null, 0, 200).entries().stream()
        .collect(Collectors.toMap(entry -> entry.asset().getName(), AssetCatalogEntry::accessible));
  }

  private static List<String> names(AssetCatalogPage page) {
    return page.entries().stream().map(entry -> entry.asset().getName()).toList();
  }

  private static AssetCatalogEntry entryFor(List<AssetCatalogEntry> entries, UUID id) {
    return entries.stream()
        .filter(entry -> entry.asset().getId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private UUID createOrganization(String name) {
    return organizationRepository
        .save(new Organization(UUID.randomUUID(), name + " " + UUID.randomUUID()))
        .getId();
  }

  private UUID createUser(UUID organizationId, String displayName) {
    User user =
        new User(
            "catalog-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private UUID createGroup(UUID organizationId, String name, UUID... members) {
    Group created =
        new Group(organizationId, GroupKind.AD_HOC, name, "Ad-hoc-Gruppe", null, null, null, null);
    created.release(true);
    for (UUID memberId : members) {
      created.addMembership(new GroupMembership(memberId, organizationId));
    }
    return groupRepository.save(created).getId();
  }

  private CurrentUser callerOf(UUID userId) {
    return callerOf(userId, false);
  }

  private CurrentUser callerOf(UUID userId, boolean systemAdmin) {
    UUID organizationId = userId.equals(foreigner) ? foreignOrganization : this.organization;
    return CurrentUser.of(
        userId,
        organizationId,
        systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER,
        "Sachbearbeitung");
  }
}
