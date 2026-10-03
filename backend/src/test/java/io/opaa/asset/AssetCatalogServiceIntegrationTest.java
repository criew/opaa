package io.opaa.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetOrigin;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetType;
import io.opaa.prompt.PromptContent;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptLibraryRepository;
import io.opaa.prompt.PromptService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The catalog of #1904 over both asset types: exactly what the caller may read (direct grant, group
 * grant, organization-wide), from one query on the shell - by the rights formula alone, with the
 * organization boundary, the search and the paging in SQL. An asset the caller may not read never
 * appears, not even by name (#2092).
 */
@OpaaIntegrationTest
class AssetCatalogServiceIntegrationTest {

  /** Released to "Alle Konten" - the organization-wide reach, a grant like any other. */
  private static final boolean ALL_ACCOUNTS = true;

  private static final boolean OWNER_ONLY = false;

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
  @Autowired private PromptService promptService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceAssetAssociationService associationService;
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
  void everyoneSeesExactlyWhatTheyMayReadOfBothTypes() {
    Map<String, UUID> assets = createTheThreeStagesOfBothTypes();

    assertThat(namesFor(callerOf(owner)))
        .as("the owner reads all six")
        .containsExactlyInAnyOrderElementsOf(assets.keySet());
    assertThat(namesFor(callerOf(member)))
        .containsExactlyInAnyOrder(
            "Gruppe Wissen", "Gruppe Prompts", "Organisation Wissen", "Organisation Prompts");
    assertThat(namesFor(callerOf(outsider)))
        .containsExactlyInAnyOrder("Organisation Wissen", "Organisation Prompts");
  }

  @Test
  void anAssetTheCallerMayNotReadIsFoundNeitherByListingNorBySearch() {
    createTheThreeStagesOfBothTypes();
    UUID closed =
        promptLibraryRepository
            .save(
                PromptLibrary.ownedByUser(
                    organization, "Geschlossene Vorlagen", "Personalsachen Referat 12", owner))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            PromptLibrary.ASSET_TYPE, closed, organization, owner, AssetRole.OWNER, null, owner));

    assertThat(namesFor(callerOf(outsider))).doesNotContain("Geschlossene Vorlagen");
    for (String query : List.of("Geschlossene", "Personalsachen")) {
      AssetCatalogPage page = catalogService.list(callerOf(outsider), null, query, 0, 50);
      assertThat(page.entries())
          .as("neither the name nor the description reveals it: %s", query)
          .isEmpty();
      assertThat(page.totalElements()).as("not even its existence counts").isZero();
    }
    assertThat(entryIds(callerOf(owner))).contains(closed);
  }

  @Test
  void aCallerWhoMayReadNothingGetsAnEmptyPage() {
    createTheThreeStagesOfBothTypes();
    UUID foreignOwner = createUser(foreignOrganization, "Fremde Eigentümerin");
    knowledgeLibrary(foreignOrganization, "Fremd privat", foreignOwner, OWNER_ONLY);

    AssetCatalogPage page = catalogService.list(callerOf(foreigner), null, null, 0, 50);

    assertThat(page.entries()).isEmpty();
    assertThat(page.totalElements()).isZero();
    assertThat(page.totalPages()).isZero();
  }

  @Test
  void aSystemAdministratorWithoutAGrantSeesNoMoreThanTheFormulaGrants() {
    createTheThreeStagesOfBothTypes();

    assertThat(namesFor(callerOf(administrator, true)))
        .as("administering is never reading: the catalog follows the formula alone")
        .containsExactlyInAnyOrder("Organisation Wissen", "Organisation Prompts");
  }

  @Test
  void theCatalogNeverCrossesTheOrganizationBoundary() {
    createTheThreeStagesOfBothTypes();
    UUID foreignOwner = createUser(foreignOrganization, "Fremde Eigentümerin");
    promptLibrary(foreignOrganization, "Fremd privat", foreignOwner, OWNER_ONLY);
    knowledgeLibrary(foreignOrganization, "Fremd organisationsweit", foreignOwner, ALL_ACCOUNTS);

    assertThat(namesFor(callerOf(owner))).doesNotContain("Fremd privat", "Fremd organisationsweit");
    assertThat(namesFor(callerOf(administrator, true)))
        .doesNotContain("Fremd privat", "Fremd organisationsweit");
    assertThat(namesFor(callerOf(foreigner))).containsExactly("Fremd organisationsweit");
  }

  @Test
  void anEntryNamesTypeOwnerAndOrigin() {
    UUID groupOwned =
        promptLibraryRepository
            .save(
                PromptLibrary.ownedByGroup(organization, "Referatsvorlagen", "Hausstandard", group))
            .getId();
    releaseToAllAccounts(PromptLibrary.ASSET_TYPE, groupOwned, organization, owner, ALL_ACCOUNTS);
    UUID personOwned = knowledgeLibrary(organization, "Rechtsquellen", owner, ALL_ACCOUNTS);

    List<AssetCatalogEntry> entries =
        catalogService.list(callerOf(outsider), null, null, 0, 50).entries();

    AssetCatalogEntry prompts = entryFor(entries, groupOwned);
    assertThat(prompts.asset().getAssetType()).isEqualTo(PromptLibrary.ASSET_TYPE);
    assertThat(prompts.asset().getDescription()).isEqualTo("Hausstandard");
    assertThat(prompts.asset().getOrigin()).isEqualTo(AssetOrigin.LOCAL);
    assertThat(prompts.ownerLabel()).as("the owning group, not a person").isEqualTo("Referat 50");
    AssetCatalogEntry knowledge = entryFor(entries, personOwned);
    assertThat(knowledge.asset().getAssetType()).isEqualTo(KnowledgeLibrary.ASSET_TYPE);
    assertThat(knowledge.ownerLabel()).isEqualTo("Eigentümerin");
    assertThat(knowledge.succession()).isNull();
  }

  @Test
  void anEntryCarriesItsExtentAndItsSpreadOverSpaces() {
    UUID prompts = promptLibrary(organization, "Vorlagen", owner, ALL_ACCOUNTS);
    UUID knowledge = knowledgeLibrary(organization, "Leer", owner, ALL_ACCOUNTS);
    for (String name : List.of("anhoerung", "vermerk")) {
      promptService.create(
          prompts,
          new PromptContent(name, name, null, "Bitte formulieren.", List.of(), 0),
          callerOf(owner));
    }
    for (int i = 0; i < 2; i++) {
      associationService.associate(
          createSpace(), PromptLibrary.ASSET_TYPE, prompts, callerOf(owner));
    }

    List<AssetCatalogEntry> entries =
        catalogService.list(callerOf(outsider), null, null, 0, 50).entries();

    assertThat(entryFor(entries, prompts).itemCount()).as("its prompts").isEqualTo(2);
    assertThat(entryFor(entries, prompts).spaceCount()).isEqualTo(2);
    assertThat(entryFor(entries, knowledge).itemCount()).as("no document yet").isZero();
    assertThat(entryFor(entries, knowledge).spaceCount()).isZero();
  }

  @Test
  void anAssetWhoseOwnerLeftCarriesItsOpenSuccessionInTheCatalog() {
    UUID id = promptLibrary(organization, "Verwaist", owner, ALL_ACCOUNTS);
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", owner);

    AssetCatalogEntry entry =
        entryFor(catalogService.list(callerOf(outsider), null, null, 0, 50).entries(), id);

    assertThat(entry.succession()).isNotNull();
    assertThat(entry.succession().addressee()).isEqualTo(SuccessionAddressee.SYSTEM_ADMINISTRATION);
  }

  @Test
  void theTypeFilterAndTheSearchNarrowTheCatalogInTheQuery() {
    createTheThreeStagesOfBothTypes();
    promptLibrary(organization, "Rabatt 100%", owner, ALL_ACCOUNTS);
    promptLibrary(organization, "Rabatt 1000", owner, ALL_ACCOUNTS);
    UUID described =
        promptLibraryRepository
            .save(
                PromptLibrary.ownedByUser(
                    organization, "Vermerke", "Formulierungen für die ANHÖRUNG", owner))
            .getId();
    releaseToAllAccounts(PromptLibrary.ASSET_TYPE, described, organization, owner, ALL_ACCOUNTS);

    assertThat(
            names(catalogService.list(callerOf(outsider), PromptLibrary.ASSET_TYPE, null, 0, 50)))
        .containsExactly("Organisation Prompts", "Rabatt 100%", "Rabatt 1000", "Vermerke");
    assertThat(
            names(
                catalogService.list(callerOf(outsider), KnowledgeLibrary.ASSET_TYPE, null, 0, 50)))
        .containsExactly("Organisation Wissen");
    assertThat(names(catalogService.list(callerOf(outsider), null, "organisation", 0, 50)))
        .as("case-insensitive on the name")
        .containsExactly("Organisation Prompts", "Organisation Wissen");
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
    createTheThreeStagesOfBothTypes();

    AssetCatalogPage first = catalogService.list(callerOf(owner), null, null, 0, 4);
    AssetCatalogPage second = catalogService.list(callerOf(owner), null, null, 1, 4);

    assertThat(names(first))
        .containsExactly(
            "Gruppe Prompts", "Gruppe Wissen", "Organisation Prompts", "Organisation Wissen");
    assertThat(names(second)).containsExactly("Privat Prompts", "Privat Wissen");
    assertThat(first.totalElements()).isEqualTo(6);
    assertThat(first.totalPages()).isEqualTo(2);
    assertThat(names(catalogService.list(callerOf(owner), null, null, 5, 4))).isEmpty();
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

  /** Per type: private (owner only), shared with the group, released organization-wide. */
  private Map<String, UUID> createTheThreeStagesOfBothTypes() {
    Map<String, UUID> assets = new LinkedHashMap<>();
    for (AssetType type : TYPES) {
      String suffix = type.equals(KnowledgeLibrary.ASSET_TYPE) ? " Wissen" : " Prompts";
      assets.put("Privat" + suffix, asset(type, "Privat" + suffix, OWNER_ONLY));
      UUID shared = asset(type, "Gruppe" + suffix, OWNER_ONLY);
      grantRepository.save(
          AssetGrant.forGroup(type, shared, organization, group, AssetRole.VIEWER, null, owner));
      assets.put("Gruppe" + suffix, shared);
      assets.put("Organisation" + suffix, asset(type, "Organisation" + suffix, ALL_ACCOUNTS));
    }
    return assets;
  }

  private UUID asset(AssetType type, String name, boolean allAccounts) {
    return type.equals(KnowledgeLibrary.ASSET_TYPE)
        ? knowledgeLibrary(organization, name, owner, allAccounts)
        : promptLibrary(organization, name, owner, allAccounts);
  }

  private UUID knowledgeLibrary(
      UUID organizationId, String name, UUID ownerId, boolean allAccounts) {
    UUID id =
        knowledgeLibraryRepository
            .save(KnowledgeLibrary.ownedByUser(organizationId, name, null, ownerId))
            .getId();
    releaseToAllAccounts(KnowledgeLibrary.ASSET_TYPE, id, organizationId, ownerId, allAccounts);
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

  private UUID promptLibrary(UUID organizationId, String name, UUID ownerId, boolean allAccounts) {
    UUID id =
        promptLibraryRepository
            .save(PromptLibrary.ownedByUser(organizationId, name, null, ownerId))
            .getId();
    releaseToAllAccounts(PromptLibrary.ASSET_TYPE, id, organizationId, ownerId, allAccounts);
    grantRepository.save(
        AssetGrant.forUser(
            PromptLibrary.ASSET_TYPE, id, organizationId, ownerId, AssetRole.OWNER, null, ownerId));
    return id;
  }

  private void releaseToAllAccounts(
      AssetType type, UUID id, UUID organizationId, UUID grantedBy, boolean allAccounts) {
    if (allAccounts) {
      grantRepository.save(
          AssetGrant.forAllAccounts(type, id, organizationId, AssetRole.VIEWER, null, grantedBy));
    }
  }

  private List<String> namesFor(CurrentUser caller) {
    return names(catalogService.list(caller, null, null, 0, 200));
  }

  private List<UUID> entryIds(CurrentUser caller) {
    return catalogService.list(caller, null, null, 0, 200).entries().stream()
        .map(entry -> entry.asset().getId())
        .toList();
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

  private UUID createSpace() {
    Space space = new Space("Space", null, false, owner, organization);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organization));
    return spaceRepository.save(space).getId();
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
